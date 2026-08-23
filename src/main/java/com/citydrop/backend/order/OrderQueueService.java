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

@Service
public class OrderQueueService {

    private final StationRepository stationRepository;
    private final OrderRepository orderRepository;

    public OrderQueueService(StationRepository stationRepository, OrderRepository orderRepository) {
        this.stationRepository = stationRepository;
        this.orderRepository = orderRepository;
    }

    // Entry point for order submission. Decides: save PENDING_DROPOFF / save QUEUED / throw 409.
    // Locks the station row first, so decrement, queue check, and assignment for this station are all
    // serialized against each other and against handleVehicleAvailable.
    @Transactional
    public OrderEntity reserveVehicleOrQueue(int userId, DeliveryQuote selectedQuote, boolean queueIfUnavailable) {
        int stationId = selectedQuote.stationId();
        String vehicle = selectedQuote.vehicle();

        stationRepository.findByStationIdForUpdate(stationId);   // lock

        // Queue non-empty -> no-bypass: if anyone is waiting, a new order must not grab a vehicle.
        if (orderRepository.existsQueuedOrder(stationId, vehicle)) {
            if (queueIfUnavailable) {
                return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.QUEUED));
            }
            throw new VehicleUnavailableException();
        }

        int rowsAffected = decrement(stationId, vehicle);        // conditional decrement, SQL has AND count > 0
        if (rowsAffected == 1) {
            return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.PENDING_DROPOFF));
        }
        // rowsAffected == 0 -> out of stock. Decrement matched 0 rows and changed nothing,
        // so the QUEUED branch needs no rollback.
        if (queueIfUnavailable) {
            return orderRepository.save(newOrder(userId, selectedQuote, OrderStatus.QUEUED));
        }
        throw new VehicleUnavailableException();
    }

    // Called ONLY when an already-assigned vehicle is released: F5 scheduler on delivery completion,
    // or F1 on cancel of an already-assigned order. NOT called on queue-join.
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
            if (orderRepository.assignQueuedOrder(head.orderId()) == 1) {
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
                null
        );
    }
}