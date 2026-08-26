package com.citydrop.backend.order;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.models.responses.DeliveryQuote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class OrderQueueService {

    private final StationRepository stationRepository;
    private final OrderRepository orderRepository;

    public OrderQueueService(StationRepository stationRepository, OrderRepository orderRepository) {
        this.stationRepository = stationRepository;
        this.orderRepository = orderRepository;
    }

    // Entry point for order submission. Decides: save PENDING_DROPOFF / save QUEUED / throw 409.
    // Does NOT claim a vehicle -- placing an order is just a commitment to show up, so any number
    // of people can be PENDING_DROPOFF for the same station+vehicle at once. A vehicle is only
    // actually claimed later, at claimVehicleAtDropoff, when the package physically arrives.
    // Locks the station row first, so the read here and any decrement/assignment elsewhere for
    // this station are all serialized against each other.
    @Transactional
    public OrderEntity reserveVehicleOrQueue(int userId, DeliveryQuote selectedQuote, boolean queueIfUnavailable) {
        int stationId = selectedQuote.stationId();
        String vehicle = selectedQuote.vehicle();

        StationEntity station = stationRepository.findByStationIdForUpdate(stationId);   // lock

        // Queue non-empty -> no-bypass: if anyone is waiting, a new order must not jump ahead of them.
        if (orderRepository.existsQueuedOrder(stationId, vehicle)) {
            if (queueIfUnavailable) {
                return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.QUEUED));
            }
            throw new VehicleUnavailableException();
        }

        boolean vehicleIdleNow = idleCount(station, vehicle) > 0;
        if (vehicleIdleNow) {
            return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.PENDING_DROPOFF));
        }
        if (queueIfUnavailable) {
            return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.QUEUED));
        }
        throw new VehicleUnavailableException();
    }

    // Called when the package physically arrives at the station (OrderService.dropOff). This is
    // where a vehicle is actually claimed -- if none is idle right now (every PENDING_DROPOFF order
    // for this station+vehicle is just a commitment, not a reservation, so more people can show up
    // than there are vehicles), the order joins the queue instead of moving to AT_STATION.
    @Transactional
    public String claimVehicleAtDropoff(OrderEntity order) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stationRepository.findByStationIdForUpdate(order.stationId());   // lock

        int rowsAffected = decrement(order.stationId(), order.vehicle());   // conditional, SQL has AND count > 0
        if (rowsAffected == 1) {
            if (orderRepository.markDroppedOff(order.orderId(), now) == 1) {
                return OrderStatus.AT_STATION.name();
            }
            // Order left PENDING_DROPOFF between our caller's check and here (e.g. a concurrent
            // cancel) -- give back the vehicle we just claimed so it isn't leaked, then report the
            // conflict same as before.
            increment(order.stationId(), order.vehicle());
            throw new InvalidOrderStatusException(order.status());
        }
        // No vehicle idle -- the package is physically here, so it queues from this moment rather
        // than failing outright (unlike order submission, drop-off has no "reject" option).
        if (orderRepository.markQueuedAtDropoff(order.orderId(), now) == 1) {
            return OrderStatus.QUEUED.name();
        }
        throw new InvalidOrderStatusException(order.status());
    }

    // Called ONLY when a claimed vehicle is released: F5 scheduler on delivery completion, or F1 on
    // cancel of an order that had actually claimed one (AT_STATION or later). NOT called on
    // cancelling a PENDING_DROPOFF or QUEUED order -- neither ever held a vehicle to give back.
    @Transactional
    public void handleVehicleAvailable(int stationId, String vehicle) {
        stationRepository.findByStationIdForUpdate(stationId);   // lock

        while (true) {
            OrderEntity head = orderRepository.findOldestQueuedForUpdate(stationId, vehicle);
            if (head == null) {
                increment(stationId, vehicle);                   // nobody waiting -> vehicle goes back to stock
                return;
            }
            // Transfer the released vehicle straight to the queue head. CAS guards against a concurrent
            // cancel of the head: on success this is a transfer, so do NOT increment and do NOT re-emit
            // an availability event.
            //
            // A queued order's droppedOffAt tells us which of two different waits this is: null means
            // it queued at submission time and the package hasn't arrived yet (-> back to PENDING_DROPOFF,
            // same as before); non-null means it queued at drop-off time because the package was already
            // physically at the station with nothing free to carry it (-> straight to AT_STATION, with
            // droppedOffAt refreshed so its delivery clock starts now, from actually getting a vehicle,
            // not from whenever it happened to arrive while waiting).
            boolean queuedAtDropoff = head.droppedOffAt() != null;
            int rowsAffected = queuedAtDropoff
                    ? orderRepository.assignQueuedOrderAtStation(head.orderId(), OffsetDateTime.now(ZoneOffset.UTC))
                    : orderRepository.assignQueuedOrder(head.orderId());
            if (rowsAffected == 1) {
                return;
            }
            // CAS failed (head cancelled/changed concurrently) -> try the next-oldest.
        }
    }

    private int idleCount(StationEntity station, String vehicle) {
        return VehicleType.valueOf(vehicle) == VehicleType.ROBOT
                ? station.robotCount()
                : station.droneCount();
    }

    private int decrement(int stationId, String vehicle) {
        return VehicleType.valueOf(vehicle) == VehicleType.ROBOT
                ? stationRepository.decrementRobotCount(stationId)
                : stationRepository.decrementDroneCount(stationId);
    }

    private int increment(int stationId, String vehicle) {
        return VehicleType.valueOf(vehicle) == VehicleType.ROBOT
                ? stationRepository.incrementRobotCount(stationId)
                : stationRepository.incrementDroneCount(stationId);
    }

    // Price/time are frozen at submit/queue-join time from selectedQuote and never recomputed later
    // (including on QUEUED -> assignment).
    // orderId = 0 (@Id auto-increment, filled in after insert); droppedOffAt = null for a new order.
    private OrderEntity newOrder(int userId, DeliveryQuote q, OrderStatus status) {
        return new OrderEntity(
                0,
                userId,
                q.destination(),
                q.packageWeightLbs(),
                q.price(),
                q.time(),
                q.vehicle(),
                q.stationId(),
                status.name(),
                OffsetDateTime.now(),
                null,
                true
        );
    }
}