package com.citydrop.backend.order;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
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

    // Entry point for order submission. Always creates PENDING_DROPOFF -- placing an order is
    // just a commitment to show up, never a vehicle claim, so any number of people can be
    // PENDING_DROPOFF for the same station+vehicle at once. A vehicle is only ever actually
    // claimed later, at claimVehicleAtDropoff, when the package physically arrives.
    // queueIfUnavailable is persisted on the order and consulted there, not here -- there's no
    // scarcity to check at submission time since PENDING_DROPOFF doesn't reserve anything.
    @Transactional
    public OrderEntity reserveVehicleOrQueue(int userId, DeliveryQuote selectedQuote, boolean queueIfUnavailable) {
        return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.PENDING_DROPOFF, queueIfUnavailable));
    }

    // Called when the package physically arrives at the station (OrderService.dropOff). This is
    // where a vehicle is actually claimed -- if none is idle right now (every PENDING_DROPOFF order
    // for this station+vehicle is just a commitment, not a reservation, so more people can show up
    // than there are vehicles), the order either joins the queue (queueIfUnavailable) or fails.
    @Transactional
    public String claimVehicleAtDropoff(OrderEntity order) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stationRepository.findByStationIdForUpdate(order.stationId());   // lock

        int rowsAffected = decrement(order.stationId(), order.vehicle());   // conditional, SQL has AND count > 0
        if (rowsAffected == 1) {
            if (orderRepository.markDroppedOff(order.orderId(), now) == 1) {
                return OrderStatus.BEFORE_HALF_WAY.name();
            }
            // Order left PENDING_DROPOFF between our caller's check and here (e.g. a concurrent
            // cancel) -- give back the vehicle we just claimed so it isn't leaked, then report the
            // conflict same as before.
            increment(order.stationId(), order.vehicle());
            throw new InvalidOrderStatusException(order.status());
        }
        // No vehicle idle. If the order opted into queueIfUnavailable, it queues from this moment
        // (the package is physically here now). Otherwise this is the same conflict submission
        // would have thrown had a vehicle never been available -- the customer should only have
        // chosen not to queue if they saw one was available, so hitting this means it was taken
        // out from under them between then and now.
        if (order.queueIfUnavailable()) {
            if (orderRepository.markQueuedAtDropoff(order.orderId(), now) == 1) {
                return OrderStatus.QUEUED.name();
            }
            throw new InvalidOrderStatusException(order.status());
        }
        throw new VehicleUnavailableException();
    }

    // Called ONLY when a claimed vehicle is released: F5 scheduler on delivery completion, or F1 on
    // cancel of an order that had actually claimed one (BEFORE_HALF_WAY or later). NOT called on
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
            // Transfer the released vehicle straight to the queue head (a QUEUED order only ever
            // exists post-dropoff now, so this always moves it to BEFORE_HALF_WAY -- see
            // claimVehicleAtDropoff/markQueuedAtDropoff). CAS guards against a concurrent cancel of
            // the head: on success this is a transfer, so do NOT increment and do NOT re-emit an
            // availability event.
            if (orderRepository.assignQueuedOrderAtStation(head.orderId(), OffsetDateTime.now(ZoneOffset.UTC)) == 1) {
                return;
            }
            // CAS failed (head cancelled/changed concurrently) -> try the next-oldest.
        }
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

    // Price/time are frozen at submit time from selectedQuote and never recomputed later.
    // orderId = 0 (@Id auto-increment, filled in after insert); droppedOffAt = null for a new order.
    private OrderEntity newOrder(int userId, DeliveryQuote q, OrderStatus status, boolean queueIfUnavailable) {
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
                true,
                queueIfUnavailable
        );
    }
}