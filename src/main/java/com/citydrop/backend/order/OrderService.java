package com.citydrop.backend.order;

import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.entities.OrderEntity;
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

        // Submission always succeeds as PENDING_DROPOFF -- it's just a commitment, not a vehicle
        // claim. If no vehicle turns out to be idle at drop-off, the order queues automatically.
        OrderEntity savedOrder = orderQueueService.reserveVehicleOrQueue(userId, selectedQuote);

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

    // A vehicle is claimed here, not at submitOrder -- see OrderQueueService.claimVehicleAtDropoff.
    // Returns BEFORE_HALF_WAY if one was idle, QUEUED if the package arrived with none free.
    public String dropOff(int userId, int orderId) {
        OrderEntity order = getOrderEntity(userId, orderId); // 404 if missing
        if (!order.status().equals(OrderStatus.PENDING_DROPOFF.name())) {
            throw new InvalidOrderStatusException(order.status()); // 409, already past PENDING_DROPOFF
        }
        return orderQueueService.claimVehicleAtDropoff(order);
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

        // not infinite loop because order's progression direction is linear, meaning any status update eventually
        // results in one of the terminal conditions
        while (true) {
            if (OrderStatus.valueOf(order.status()).isTerminal()) {
                throw new InvalidOrderStatusException(order.status()); // 409, already delivered/cancelled
            }

            if (orderRepository.updateStatus(orderId, order.status(), OrderStatus.CANCELLED.name()) == 1) {
                // PENDING_DROPOFF is just a commitment to show up -- it never claimed a vehicle
                // (see OrderQueueService.claimVehicleAtDropoff), so there's nothing to release.
                // Everything past that (BEFORE_HALF_WAY onward) does hold one.
                if (!order.status().equals(OrderStatus.PENDING_DROPOFF.name())) {
                    orderQueueService.handleVehicleAvailable(order.stationId(), order.vehicle());
                }
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
