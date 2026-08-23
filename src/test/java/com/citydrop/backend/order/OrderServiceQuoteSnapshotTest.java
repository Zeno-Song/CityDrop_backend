package com.citydrop.backend.order;

import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.models.requests.SubmissionObject;
import com.citydrop.backend.models.responses.DeliveryQuote;
import com.citydrop.backend.models.responses.OrderObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceQuoteSnapshotTest {

    private OrderRepository orderRepository;
    private StationRepository stationRepository;
    private QuoteSnapshotCache quoteSnapshotCache;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        stationRepository = mock(StationRepository.class);
        quoteSnapshotCache = mock(QuoteSnapshotCache.class);
        orderService = new OrderService(orderRepository, stationRepository, quoteSnapshotCache);
    }

    @Test
    void missingOrMismatchedSnapshotReturnsQuoteExpired() {
        SubmissionObject submission = submission();
        when(quoteSnapshotCache.findMatching(42, submission.destination(), 4.0, 3, "ROBOT"))
                .thenReturn(Optional.empty());

        assertThrows(QuoteExpiredException.class, () -> orderService.submitOrder(42, submission));
        verify(stationRepository, never()).decrementRobotCount(3);
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    @Test
    void orderUsesPriceAndTimeFromTheLockedQuote() {
        SubmissionObject submission = submission();
        DeliveryQuote lockedQuote = new DeliveryQuote(
                submission.destination(),
                submission.packageWeightLbs(),
                "ROBOT",
                12.50,
                18.0,
                3
        );
        when(quoteSnapshotCache.findMatching(42, submission.destination(), 4.0, 3, "ROBOT"))
                .thenReturn(Optional.of(lockedQuote));
        when(stationRepository.decrementRobotCount(3)).thenReturn(1);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            return new OrderEntity(
                    99,
                    order.userId(),
                    order.destination(),
                    order.packageWeightLbs(),
                    order.price(),
                    order.time(),
                    order.vehicle(),
                    order.stationId(),
                    order.status(),
                    order.createdAt(),
                    order.droppedOffAt()
            );
        });

        OrderObject result = orderService.submitOrder(42, submission);

        ArgumentCaptor<OrderEntity> savedOrder = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderRepository).save(savedOrder.capture());
        assertEquals(12.50, savedOrder.getValue().price());
        assertEquals(18.0, savedOrder.getValue().time());
        assertEquals(99, result.orderId());
    }

    private SubmissionObject submission() {
        return new SubmissionObject("1 Main St, San Francisco", 4.0, 3, "ROBOT");
    }
}
