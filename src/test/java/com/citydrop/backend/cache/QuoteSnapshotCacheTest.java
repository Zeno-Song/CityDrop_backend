package com.citydrop.backend.cache;

import com.citydrop.backend.models.responses.DeliveryQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuoteSnapshotCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private QuoteSnapshotCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        objectMapper = mock(ObjectMapper.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new QuoteSnapshotCache(
                redisTemplate,
                objectMapper,
                new CacheProperties(Duration.ofMinutes(5), null, null)
        );
    }

    @Test
    void putStoresTheQuoteSnapshotWithTheConfiguredTtl() {
        DeliveryQuote quote = quote();
        when(objectMapper.writeValueAsString(any())).thenReturn("snapshot-json");

        cache.put(42, List.of(quote));

        ArgumentCaptor<QuoteSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QuoteSnapshot.class);
        verify(objectMapper).writeValueAsString(snapshotCaptor.capture());
        verify(valueOperations).set(
                "quoteSnapshot:42",
                "snapshot-json",
                Duration.ofMinutes(5)
        );

        QuoteSnapshot stored = snapshotCaptor.getValue();
        assertEquals(42, stored.userId());
        assertEquals(List.of(quote), stored.quotes());
        assertEquals(Duration.ofMinutes(5), Duration.between(stored.createdAt(), stored.expiresAt()));
    }

    @Test
    void getEvictsAnExpiredSnapshot() {
        QuoteSnapshot expired = new QuoteSnapshot(
                42,
                Instant.now().minus(Duration.ofMinutes(10)),
                Instant.now().minus(Duration.ofMinutes(5)),
                List.of(quote())
        );
        when(valueOperations.get("quoteSnapshot:42")).thenReturn("expired-json");
        when(objectMapper.readValue("expired-json", QuoteSnapshot.class)).thenReturn(expired);

        assertNull(cache.get(42));
        verify(redisTemplate).delete("quoteSnapshot:42");
    }

    @Test
    void findMatchingUsesTheLockedQuoteValues() {
        DeliveryQuote quote = quote();
        QuoteSnapshot active = new QuoteSnapshot(
                42,
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(5)),
                List.of(quote)
        );
        when(valueOperations.get("quoteSnapshot:42")).thenReturn("active-json");
        when(objectMapper.readValue("active-json", QuoteSnapshot.class)).thenReturn(active);

        Optional<DeliveryQuote> result = cache.findMatching(
                42,
                "  1 MAIN   ST, San Francisco ",
                4.00001,
                3,
                "robot"
        );

        assertTrue(result.isPresent());
        assertEquals(quote, result.get());
    }

    private DeliveryQuote quote() {
        return new DeliveryQuote(
                "1 Main St, San Francisco",
                4.0,
                "ROBOT",
                12.50,
                18.0,
                3
        );
    }
}
