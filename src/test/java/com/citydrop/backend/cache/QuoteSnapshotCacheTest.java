package com.citydrop.backend.cache;

import com.citydrop.backend.cache.QuoteSnapshot.TimedQuote;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void putStoresTheQuoteWithTheConfiguredTtl() {
        DeliveryQuote quote = quote(3, "ROBOT");
        when(valueOperations.get("quoteSnapshot:42")).thenReturn(null); // nothing cached yet
        when(objectMapper.writeValueAsString(any())).thenReturn("snapshot-json");

        cache.put(42, List.of(quote));

        ArgumentCaptor<QuoteSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QuoteSnapshot.class);
        verify(objectMapper).writeValueAsString(snapshotCaptor.capture());
        verify(valueOperations).set("quoteSnapshot:42", "snapshot-json", Duration.ofMinutes(5));

        QuoteSnapshot stored = snapshotCaptor.getValue();
        assertEquals(42, stored.userId());
        assertEquals(1, stored.quotes().size());
        assertEquals(quote, stored.quotes().get(0).quote());
    }

    @Test
    void putMergesANewFetchWithAnUnexpiredEarlierOneForADifferentDestination() {
        // Simulates: user fetched a quote for station 3 (still within its lock window),
        // then fetched a different destination that only returns a quote for station 7.
        // The station-3 quote must survive instead of being clobbered.
        DeliveryQuote stationThreeQuote = quote(3, "ROBOT");
        TimedQuote existingEntry = new TimedQuote(stationThreeQuote, Instant.now().plus(Duration.ofMinutes(3)));
        when(valueOperations.get("quoteSnapshot:42")).thenReturn("existing-json");
        when(objectMapper.readValue("existing-json", QuoteSnapshot.class))
                .thenReturn(new QuoteSnapshot(42, List.of(existingEntry)));
        when(objectMapper.writeValueAsString(any())).thenReturn("merged-json");

        DeliveryQuote stationSevenQuote = quote(7, "DRONE");
        cache.put(42, List.of(stationSevenQuote));

        ArgumentCaptor<QuoteSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QuoteSnapshot.class);
        verify(objectMapper).writeValueAsString(snapshotCaptor.capture());
        List<DeliveryQuote> mergedQuotes = snapshotCaptor.getValue().quotes().stream()
                .map(TimedQuote::quote).toList();
        assertTrue(mergedQuotes.contains(stationThreeQuote), "earlier unexpired quote should survive the merge");
        assertTrue(mergedQuotes.contains(stationSevenQuote), "newly fetched quote should be included");
    }

    @Test
    void putDropsAnExpiredEarlierEntryInsteadOfKeepingItForever() {
        TimedQuote expiredEntry = new TimedQuote(quote(3, "ROBOT"), Instant.now().minus(Duration.ofSeconds(1)));
        when(valueOperations.get("quoteSnapshot:42")).thenReturn("existing-json");
        when(objectMapper.readValue("existing-json", QuoteSnapshot.class))
                .thenReturn(new QuoteSnapshot(42, List.of(expiredEntry)));
        when(objectMapper.writeValueAsString(any())).thenReturn("merged-json");

        cache.put(42, List.of(quote(7, "DRONE")));

        ArgumentCaptor<QuoteSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QuoteSnapshot.class);
        verify(objectMapper).writeValueAsString(snapshotCaptor.capture());
        assertEquals(1, snapshotCaptor.getValue().quotes().size());
    }

    @Test
    void findMatchingIgnoresAnExpiredEntry() {
        TimedQuote expiredEntry = new TimedQuote(quote(3, "ROBOT"), Instant.now().minus(Duration.ofSeconds(1)));
        when(valueOperations.get("quoteSnapshot:42")).thenReturn("expired-json");
        when(objectMapper.readValue("expired-json", QuoteSnapshot.class))
                .thenReturn(new QuoteSnapshot(42, List.of(expiredEntry)));

        assertNull(cache.get(42));
    }

    @Test
    void findMatchingUsesTheLockedQuoteValues() {
        DeliveryQuote quote = quote(3, "ROBOT");
        TimedQuote activeEntry = new TimedQuote(quote, Instant.now().plus(Duration.ofMinutes(5)));
        when(valueOperations.get("quoteSnapshot:42")).thenReturn("active-json");
        when(objectMapper.readValue("active-json", QuoteSnapshot.class))
                .thenReturn(new QuoteSnapshot(42, List.of(activeEntry)));

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

    @Test
    void getThrowsWhenRedisReadFails() {
        when(valueOperations.get("quoteSnapshot:42")).thenThrow(new RuntimeException("connection refused"));

        assertThrows(QuoteCacheUnavailableException.class, () -> cache.get(42));
    }

    @Test
    void findMatchingThrowsRatherThanLookingLikeAnExpiredQuoteWhenRedisIsDown() {
        when(valueOperations.get("quoteSnapshot:42")).thenThrow(new RuntimeException("connection refused"));

        assertThrows(QuoteCacheUnavailableException.class,
                () -> cache.findMatching(42, "1 Main St, San Francisco", 4.0, 3, "ROBOT"));
    }

    @Test
    void putThrowsWhenRedisWriteFails() {
        when(valueOperations.get("quoteSnapshot:42")).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("snapshot-json");
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(valueOperations).set("quoteSnapshot:42", "snapshot-json", Duration.ofMinutes(5));

        assertThrows(QuoteCacheUnavailableException.class, () -> cache.put(42, List.of(quote(3, "ROBOT"))));
    }

    private DeliveryQuote quote(int stationId, String vehicle) {
        return new DeliveryQuote(
                "1 Main St, San Francisco",
                4.0,
                vehicle,
                12.50,
                18.0,
                stationId,
                true
        );
    }
}
