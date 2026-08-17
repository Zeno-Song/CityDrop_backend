package com.citydrop.backend.order;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.deliveryOption.DeliveryService;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.models.responses.CancelOrderResponse;
import com.citydrop.backend.models.responses.OrderIdEntry;
import com.citydrop.backend.models.responses.OrderListResponse;
import com.citydrop.backend.models.responses.OrderObject;
import org.springframework.stereotype.Service;

import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.models.requests.SubmissionObject;
import com.citydrop.backend.models.responses.DeliveryQuote;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.util.List;
import java.util.stream.Stream;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final StationRepository stationRepository;
    private final DeliveryService deliveryService;

    public OrderService(
            OrderRepository orderRepository,
            StationRepository stationRepository,
            DeliveryService deliveryService
    ) {
        this.orderRepository = orderRepository;
        this.stationRepository = stationRepository;
        this.deliveryService = deliveryService;
    }
    @Transactional
    public OrderObject submitOrder(int userId, SubmissionObject order) {
        DeliveryQuote selectedQuote = deliveryService
                .getDeliveryOptions(
                        order.destination(),
                        order.packageWeightLbs()
                )
                .stream()
                .filter(quote ->
                        quote.stationId() == order.stationId()
                )
                .filter(quote ->
                        quote.vehicle().equalsIgnoreCase(order.vehicle())
                )
                .findFirst()
                .orElseThrow(VehicleUnavailableException::new);

        int updatedVehicleCount = switch (
                VehicleType.valueOf(selectedQuote.vehicle())
                ) {
            case ROBOT ->
                    stationRepository.decrementRobotCount(
                            selectedQuote.stationId()
                    );
            case DRONE ->
                    stationRepository.decrementDroneCount(
                            selectedQuote.stationId()
                    );
        };

        if (updatedVehicleCount == 0) {
            throw new VehicleUnavailableException();
        }

        OrderEntity savedOrder = orderRepository.save(
                new OrderEntity(
                        0,
                        userId,
                        selectedQuote.destination(),
                        selectedQuote.packageWeightLbs(),
                        selectedQuote.price(),
                        selectedQuote.time(),
                        selectedQuote.vehicle(),
                        selectedQuote.stationId(),
                        OrderStatus.PENDING_DROPOFF.name(),
                        OffsetDateTime.now(ZoneOffset.UTC).toString()
                )
        );

        return toOrderObject(savedOrder);
    }

    /**
     * Cancels an order owned by the user, provided it has not been delivered or already
     * cancelled. Releases the vehicle that was reserved for the order (treated as instantly
     * available again, regardless of trip progress) and returns the updated order together
     * with its refund eligibility (eligible only if it was PENDING_DROPOFF or AT_STATION).
     */
    @Transactional
    public CancelOrderResponse cancelOrder(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId);

        OrderStatus currentStatus = OrderStatus.valueOf(order.status());
        if (currentStatus == OrderStatus.DELIVERED || currentStatus == OrderStatus.CANCELLED) {
            throw new InvalidOrderStatusException(order.status());
        }

        boolean refundEligible = currentStatus == OrderStatus.PENDING_DROPOFF
                || currentStatus == OrderStatus.AT_STATION;

        int rowsAffected = orderRepository.updateStatus(
                orderId, order.status(), OrderStatus.CANCELLED.name()
        );
        if (rowsAffected == 0) {
            throw new InvalidOrderStatusException(order.status());
        }

        switch (VehicleType.valueOf(order.vehicle())) {
            case ROBOT -> stationRepository.incrementRobotCount(order.stationId());
            case DRONE -> stationRepository.incrementDroneCount(order.stationId());
        }

        OrderObject cancelledOrder = toOrderObject(order, OrderStatus.CANCELLED.name());
        return new CancelOrderResponse(cancelledOrder, refundEligible);
    }

    /** TODO: update getOrder and listOrder with proper lazy (on-call) vehicle count and status update function */
    public OrderObject getOrder(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId);

        return toOrderObject(order);
    }

    public OrderListResponse listOrder(int userId) {
        String deliveredStatus = OrderStatus.DELIVERED.name();
        String cancelledStatus = OrderStatus.CANCELLED.name();

        List<OrderIdEntry> active = orderRepository
                .findByUserIdAndStatusNot(userId, deliveredStatus)
                .stream()
                .filter(order -> !cancelledStatus.equals(order.status()))
                .map(order -> new OrderIdEntry(order.orderId()))
                .toList();

        List<OrderIdEntry> completed = Stream.concat(
                        orderRepository.findByUserIdAndStatus(userId, deliveredStatus).stream(),
                        orderRepository.findByUserIdAndStatus(userId, cancelledStatus).stream())
                .map(order -> new OrderIdEntry(order.orderId()))
                .toList();

        return new OrderListResponse(active, completed);
    }

    public String updateStatus(int userId, int orderId, String newStatus) {
        OrderEntity order = getOrderEntity(userId, orderId);
        String oldStatus = order.status();

        // guards against illegal status changes
        if (OrderStatus.valueOf(newStatus).ordinal() <= OrderStatus.valueOf(oldStatus).ordinal()) {
            throw new InvalidOrderStatusException(order.status());
        }

        int rowsAffected = orderRepository.updateStatus(
                orderId, oldStatus, newStatus
        );

        // guards against concurrent requests
        if (rowsAffected == 0) {
            throw new InvalidOrderStatusException(order.status());
        }

        return newStatus;
    }

    private OrderEntity getOrderEntity(int userId, int orderId) {
        return orderRepository
                .findByUserIdAndOrderId(userId, orderId)
                .orElseThrow(OrderNotFoundException::new);
    }

    private OrderObject toOrderObject(OrderEntity order) {
        return toOrderObject(order, order.status());
    }

    private OrderObject toOrderObject(OrderEntity order, String status) {
        return new OrderObject(
                order.orderId(),
                order.destination(),
                order.packageWeightLbs(),
                order.price(),
                order.time(),
                order.vehicle(),
                order.stationId(),
                status,
                order.createdAt()
        );
    }
}