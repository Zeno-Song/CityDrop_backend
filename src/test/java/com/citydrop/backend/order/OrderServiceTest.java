package com.citydrop.backend.order;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.deliveryOption.DeliveryService;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.models.responses.CancelOrderResponse;
import com.citydrop.backend.models.responses.OrderListResponse;
import com.citydrop.backend.models.responses.OrderObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private StationRepository stationRepository;

    @Mock
    private DeliveryService deliveryService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void getOrderReturnsOrderBelongingToUser() {
        int userId = 7;
        int orderId = 42;

        OrderEntity entity = createOrder(
                orderId,
                userId,
                OrderStatus.PENDING_DROPOFF.name()
        );

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.of(entity));

        OrderObject result = orderService.getOrder(userId, orderId);

        assertEquals(orderId, result.orderId());
        assertEquals("123 Main St", result.destination());
        assertEquals("ROBOT", result.vehicle());
        assertEquals(30.0, result.time());
    }

    @Test
    void getOrderThrowsWhenOrderDoesNotExist() {
        int userId = 7;
        int orderId = 999;

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.getOrder(userId, orderId)
        );
    }

    @Test
    void listOrderSeparatesActiveAndCompletedOrders() {
        int userId = 7;

        OrderEntity activeOrder = createOrder(
                1,
                userId,
                OrderStatus.PENDING_DROPOFF.name()
        );

        OrderEntity completedOrder = createOrder(
                2,
                userId,
                OrderStatus.DELIVERED.name()
        );

        when(orderRepository.findByUserIdAndStatusNot(
                userId,
                OrderStatus.DELIVERED.name()
        )).thenReturn(List.of(activeOrder));

        when(orderRepository.findByUserIdAndStatus(
                userId,
                OrderStatus.DELIVERED.name()
        )).thenReturn(List.of(completedOrder));

        OrderListResponse result = orderService.listOrder(userId);

        assertEquals(1, result.active().size());
        assertEquals(1, result.active().getFirst().orderId());
        assertEquals(1, result.completed().size());
        assertEquals(2, result.completed().getFirst().orderId());
    }

    @Test
    void cancelPendingDropoffOrderIsRefundEligibleAndReleasesVehicle() {
        int userId = 7;
        int orderId = 42;

        OrderEntity entity = createOrder(orderId, userId, OrderStatus.PENDING_DROPOFF.name());

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.of(entity));
        when(orderRepository.updateStatus(
                orderId, OrderStatus.PENDING_DROPOFF.name(), OrderStatus.CANCELLED.name()))
                .thenReturn(1);

        CancelOrderResponse result = orderService.cancelOrder(userId, orderId);

        assertEquals(OrderStatus.CANCELLED.name(), result.order().status());
        assertTrue(result.refundEligible());
        verify(stationRepository).incrementRobotCount(entity.stationId());
    }

    @Test
    void cancelAtStationOrderIsRefundEligible() {
        int userId = 7;
        int orderId = 42;

        OrderEntity entity = createOrder(orderId, userId, OrderStatus.AT_STATION.name());

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.of(entity));
        when(orderRepository.updateStatus(
                orderId, OrderStatus.AT_STATION.name(), OrderStatus.CANCELLED.name()))
                .thenReturn(1);

        CancelOrderResponse result = orderService.cancelOrder(userId, orderId);

        assertTrue(result.refundEligible());
    }

    @Test
    void cancelBeforeHalfWayOrderIsNotRefundEligible() {
        int userId = 7;
        int orderId = 42;

        OrderEntity entity = createOrder(orderId, userId, OrderStatus.BEFORE_HALF_WAY.name());

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.of(entity));
        when(orderRepository.updateStatus(
                orderId, OrderStatus.BEFORE_HALF_WAY.name(), OrderStatus.CANCELLED.name()))
                .thenReturn(1);

        CancelOrderResponse result = orderService.cancelOrder(userId, orderId);

        assertEquals(OrderStatus.CANCELLED.name(), result.order().status());
        assertFalse(result.refundEligible());
        verify(stationRepository).incrementRobotCount(entity.stationId());
    }

    @Test
    void cancelDeliveredOrderThrows() {
        int userId = 7;
        int orderId = 42;

        OrderEntity entity = createOrder(orderId, userId, OrderStatus.DELIVERED.name());

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.of(entity));

        assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.cancelOrder(userId, orderId)
        );
    }

    @Test
    void cancelAlreadyCancelledOrderThrows() {
        int userId = 7;
        int orderId = 42;

        OrderEntity entity = createOrder(orderId, userId, OrderStatus.CANCELLED.name());

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.of(entity));

        assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.cancelOrder(userId, orderId)
        );
    }

    @Test
    void cancelNonExistentOrderThrows() {
        int userId = 7;
        int orderId = 999;

        when(orderRepository.findByUserIdAndOrderId(userId, orderId))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.cancelOrder(userId, orderId)
        );
    }

    private OrderEntity createOrder(
            int orderId,
            int userId,
            String status
    ) {
        return new OrderEntity(
                orderId,
                userId,
                "123 Main St",
                5.0,
                12.50,
                30.0,
                "ROBOT",
                1,
                status,
                "2026-08-11T12:00:00Z"
        );
    }
}