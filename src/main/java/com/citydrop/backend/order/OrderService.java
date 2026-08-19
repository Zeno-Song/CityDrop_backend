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

import java.time.Duration;
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
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        null
                )
        );

        return toOrderObject(savedOrder);
    }

    /** TODO: update getOrder and listOrder with proper lazy (on-call) vehicle count and status update function */
    public OrderObject getOrder(int userId, int orderId) {
        return toOrderObject(refreshStatus(getOrderEntity(userId, orderId)));
    }

    public OrderListResponse listOrder(int userId) {
        List<OrderEntity> refreshed = orderRepository.findByUserId(userId)
                .stream().map(this::refreshStatus).toList();

        var active = refreshed.stream()
                .filter(o -> !o.status().equals(OrderStatus.DELIVERED.name()))
                .map(o -> new OrderIdEntry(o.orderId())).toList();
        var completed = refreshed.stream()
                .filter(o -> o.status().equals(OrderStatus.DELIVERED.name()))
                .map(o -> new OrderIdEntry(o.orderId())).toList();

        return new OrderListResponse(active, completed);
    }

    private static final double HALF_WAY_BAND = 0.10;

    private OrderStatus computeProgressStatus(OrderEntity order, OffsetDateTime now) {
        OffsetDateTime droppedOffAt = OffsetDateTime.parse(order.droppedOffAt());
        double elapsedMinutes = Duration.between(droppedOffAt, now).toMillis() / 60000.0;
        double ratio = elapsedMinutes / order.time();

        if (ratio >= 1.0) return OrderStatus.DELIVERED;
        if (ratio >= 0.5 + HALF_WAY_BAND) return OrderStatus.MORE_THAN_HALF_WAY;
        if (ratio >= 0.5 - HALF_WAY_BAND) return OrderStatus.HALF_WAY;
        return OrderStatus.BEFORE_HALF_WAY;
    }

    private OrderEntity refreshStatus(OrderEntity order) {
        String status = order.status();
        if (status.equals(OrderStatus.PENDING_DROPOFF.name())
                || status.equals(OrderStatus.DELIVERED.name())) {
            return order; // idea #4: no-op at these two statuses
        }

        OrderStatus target = computeProgressStatus(order, OffsetDateTime.now(ZoneOffset.UTC));
        if (target.name().equals(status)) return order;

        // CAS guard: only the request that actually flips the row applies the vehicle-count side effect
        int rowsAffected = orderRepository.updateStatus(order.orderId(), status, target.name());
        if (rowsAffected == 0) {  // lost a race to a concurrent get/listOrder call on the same order — reload authoritative state
            return orderRepository.findByUserIdAndOrderId(order.userId(), order.orderId())
                    .orElseThrow(OrderNotFoundException::new);
        }

        if (target == OrderStatus.DELIVERED) {
            switch (VehicleType.valueOf(order.vehicle())) {
                case ROBOT -> stationRepository.incrementRobotCount(order.stationId());
                case DRONE -> stationRepository.incrementDroneCount(order.stationId());
            }
        }

        return withStatus(order, target.name());
    }

    private OrderEntity withStatus(OrderEntity order, String newStatus) {
        return new OrderEntity(
                order.orderId(),
                order.userId(),
                order.destination(),
                order.packageWeightLbs(),
                order.price(),
                order.time(),
                order.vehicle(),
                order.stationId(),
                newStatus,               // <- the one field being changed
                order.createdAt(),
                order.droppedOffAt()
        );
    }

    public String dropOff(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId); // 404 if missing
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        if (orderRepository.markDroppedOff(orderId, now) == 0) {
            throw new InvalidOrderStatusException(order.status()); // 409, already past PENDING_DROPOFF
        }
        return OrderStatus.AT_STATION.name();
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