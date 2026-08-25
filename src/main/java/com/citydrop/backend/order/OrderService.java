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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final StationRepository stationRepository;
    private final DeliveryService deliveryService;
    private final OrderQueueService orderQueueService;   // Feature 2

    public OrderService(
            OrderRepository orderRepository,
            StationRepository stationRepository,
            DeliveryService deliveryService,
            OrderQueueService orderQueueService          // Feature 2
    ) {
        this.orderRepository = orderRepository;
        this.stationRepository = stationRepository;
        this.deliveryService = deliveryService;
        this.orderQueueService = orderQueueService;
    }

    @Transactional
    public OrderObject submitOrder(int userId, SubmissionObject order) {
        // Price/time computed once inside getDeliveryOptions; do NOT recompute at submit.
        // Resolve selectedQuote first, then let the queue service decide reserve vs. queue.
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

        // Feature 2: reserve-or-queue replaces the old decrement / rows==0 -> 409 block.
        // queueIfUnavailable == false/omitted keeps the original immediate-failure behavior.
        OrderEntity savedOrder = orderQueueService.reserveVehicleOrQueue(
                userId, selectedQuote, order.queueIfUnavailable());

        return toOrderObject(savedOrder);
    }

    /** getOrder and listOrder retrieve actively updated vehicle count and statuses */
    public OrderObject getOrder(int userId, int orderId) {
        return toOrderObject(getOrderEntity(userId, orderId));
    }

    public OrderListResponse listOrder(int userId) {
        List<OrderEntity> orders = orderRepository.findByUserId(userId);

        var active = orders.stream()
                .filter(o -> !OrderStatus.valueOf(o.status()).isTerminal())
                .map(o -> new OrderIdEntry(o.orderId())).toList();
        var completed = orders.stream()
                .filter(o -> OrderStatus.valueOf(o.status()).isTerminal())
                .map(o -> new OrderIdEntry(o.orderId())).toList();

        return new OrderListResponse(active, completed);
    }

    public String dropOff(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId); // 404 if missing
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (orderRepository.markDroppedOff(orderId, now) == 0) {
            throw new InvalidOrderStatusException(order.status()); // 409, already past PENDING_DROPOFF
        }
        return OrderStatus.AT_STATION.name();
    }

    @Transactional
    public CancelOrderResponse cancelOrder(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId); // 404 if missing/not owned

        // QUEUED never had a vehicle decremented, so cancelling it needs no release and is
        // always refund-eligible. If assignQueuedOrder wins the race first, the row is now
        // PENDING_DROPOFF - re-fetch and fall through to the general (non-QUEUED) handling below.
        if (order.status().equals(OrderStatus.QUEUED.name())) {
            if (orderRepository.updateStatus(orderId, OrderStatus.QUEUED.name(), OrderStatus.CANCELLED.name()) == 1) {
                return toCancelResponse(withStatus(order, OrderStatus.CANCELLED.name()));
            }
            order = getOrderEntity(userId, orderId);
        }

        while (true) {
            if (OrderStatus.valueOf(order.status()).isTerminal()) {
                throw new InvalidOrderStatusException(order.status()); // 409, already delivered/cancelled
            }

            if (orderRepository.updateStatus(orderId, order.status(), OrderStatus.CANCELLED.name()) == 1) {
                orderQueueService.handleVehicleAvailable(order.stationId(), order.vehicle());
                return toCancelResponse(withStatus(order, OrderStatus.CANCELLED.name()));
            }
            // row changed between the read and this write (e.g. scheduler advanced it) - re-fetch and re-evaluate
            order = getOrderEntity(userId, orderId);
        }
    }

    private OrderEntity getOrderEntity(int userId, int orderId) {
        return orderRepository
                .findByUserIdAndOrderId(userId, orderId)
                .orElseThrow(OrderNotFoundException::new);
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
                newStatus,
                order.createdAt(),
                order.droppedOffAt(),
                order.refundEligible()
        );
    }

    private CancelOrderResponse toCancelResponse(OrderEntity order) {
        return new CancelOrderResponse(toOrderObject(order), order.refundEligible());
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
                order.createdAt().toString()
        );
    }
}