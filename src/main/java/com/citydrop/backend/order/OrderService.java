package com.citydrop.backend.order;

import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.entities.OrderEntity;
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
    private final OrderQueueService orderQueueService;   // Feature 2
    private final QuoteSnapshotCache quoteSnapshotCache;

    public OrderService(
            OrderRepository orderRepository,
            OrderQueueService orderQueueService,    // Feature 2
            QuoteSnapshotCache quoteSnapshotCache
    ) {
        this.orderRepository = orderRepository;
        this.orderQueueService = orderQueueService;
        this.quoteSnapshotCache = quoteSnapshotCache;
    }

    @Transactional
    public OrderObject submitOrder(int userId, SubmissionObject order) {
        DeliveryQuote selectedQuote = quoteSnapshotCache
                .findMatching(
                        userId,
                        order.destination(),
                        order.packageWeightLbs(),
                        order.stationId(),
                        order.vehicle()
                )
                .orElseThrow(QuoteExpiredException::new);

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
                order.createdAt().toString()
        );
    }
}
