package com.citydrop.backend.order;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.deliveryOption.DeliveryService;
import com.citydrop.backend.enums.OrderStatus;
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

    /** TODO: update getOrder and listOrder with proper lazy (on-call) vehicle count and status update function */
    public OrderObject getOrder(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId);

        return toOrderObject(order);
    }

    public OrderListResponse listOrder(int userId) {
        String deliveredStatus = OrderStatus.DELIVERED.name();

        List<OrderIdEntry> active = orderRepository
                .findByUserIdAndStatusNot(userId, deliveredStatus)
                .stream()
                .map(order -> new OrderIdEntry(order.orderId()))
                .toList();

        List<OrderIdEntry> completed = orderRepository
                .findByUserIdAndStatus(userId, deliveredStatus)
                .stream()
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
        return new OrderObject(
                order.orderId(),
                order.destination(),
                order.packageWeightLbs(),
                order.price(),
                order.time(),
                order.vehicle(),
                order.stationId(),
                order.status(),
                order.createdAt()
        );
    }
}