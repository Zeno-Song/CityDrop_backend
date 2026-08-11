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

    public OrderObject getOrder(int userId, int orderId) {
        OrderEntity order = orderRepository
                .findByUserIdAndOrderId(userId, orderId)
                .orElseThrow(OrderNotFoundException::new);

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