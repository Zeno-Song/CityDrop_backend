package com.citydrop.backend.order;

import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.models.requests.SubmissionObject;
import com.citydrop.backend.models.responses.DeliveryQuote;
import com.citydrop.backend.models.responses.OrderObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceQuoteSnapshotTest {

    private OrderRepository orderRepository;
    private OrderQueueService orderQueueService;
    private QuoteSnapshotCache quoteSnapshotCache;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderQueueService = mock(OrderQueueService.class);
        quoteSnapshotCache = mock(QuoteSnapshotCache.class);
        orderService = new OrderService(orderRepository, orderQueueService, quoteSnapshotCache);
    }

    @Test
    void missingOrMismatchedSnapshotReturnsQuoteExpired() {
        SubmissionObject submission = submission();
        when(quoteSnapshotCache.findMatching(42, submission.destination(), 4.0, 3, "ROBOT"))
                .thenReturn(Optional.empty());

        assertThrows(QuoteExpiredException.class, () -> orderService.submitOrder(42, submission));
        verify(orderQueueService, never()).reserveVehicleOrQueue(anyInt(), any());
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
                3,
                true
        );
        when(quoteSnapshotCache.findMatching(42, submission.destination(), 4.0, 3, "ROBOT"))
                .thenReturn(Optional.of(lockedQuote));

        OrderEntity savedOrder = new OrderEntity(
                99, 42, submission.destination(), submission.packageWeightLbs(),
                12.50, 18.0, "ROBOT", 3, "PENDING_DROPOFF",
                OffsetDateTime.now(), null, true
        );
        when(orderQueueService.reserveVehicleOrQueue(eq(42), eq(lockedQuote)))
                .thenReturn(savedOrder);

        OrderObject result = orderService.submitOrder(42, submission);

        ArgumentCaptor<DeliveryQuote> quoteCaptor = ArgumentCaptor.forClass(DeliveryQuote.class);
        verify(orderQueueService).reserveVehicleOrQueue(eq(42), quoteCaptor.capture());
        assertEquals(12.50, quoteCaptor.getValue().price());
        assertEquals(18.0, quoteCaptor.getValue().time());
        assertEquals(99, result.orderId());
    }

    private SubmissionObject submission() {
        return new SubmissionObject("1 Main St, San Francisco", 4.0, 3, "ROBOT");
    }
}