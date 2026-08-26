package com.citydrop.backend.cache;

import com.citydrop.backend.models.responses.DeliveryQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
public class QuoteSnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotCache.class);
    private static final double WEIGHT_MATCH_EPSILON = 0.0001;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties cacheProperties;

    public QuoteSnapshotCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CacheProperties cacheProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheProperties = cacheProperties;
    }

    public void put(int userId, List<DeliveryQuote> quotes) {
        Instant createdAt = Instant.now();
        QuoteSnapshot snapshot = new QuoteSnapshot(
                userId,
                createdAt,
                createdAt.plus(cacheProperties.quoteLockTtl()),
                List.copyOf(quotes)
        );
        String key = CacheKeyUtils.quoteSnapshotKey(userId);
        try {
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(snapshot),
                    cacheProperties.quoteLockTtl()
            );
        } catch (RuntimeException e) {
            log.warn("Failed to write quote snapshot cache for key '{}': {}", key, e.getMessage());
        }
    }

    public QuoteSnapshot get(int userId) {
        String key = CacheKeyUtils.quoteSnapshotKey(userId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                return null;
            }
            QuoteSnapshot snapshot = objectMapper.readValue(cached, QuoteSnapshot.class);
            if (!snapshot.expiresAt().isAfter(Instant.now())) {
                evict(userId);
                return null;
            }
            return snapshot;
        } catch (RuntimeException e) {
            log.warn("Failed to read quote snapshot cache for key '{}': {}", key, e.getMessage());
            return null;
        }
    }

    public Optional<DeliveryQuote> findMatching(
            int userId,
            String destination,
            double packageWeightLbs,
            int stationId,
            String vehicle
    ) {
        QuoteSnapshot snapshot = get(userId);
        if (snapshot == null) {
            return Optional.empty();
        }

        return snapshot.quotes().stream()
                .filter(quote -> quote.stationId() == stationId)
                .filter(quote -> quote.vehicle().equalsIgnoreCase(vehicle))
                .filter(quote -> sameDestination(quote.destination(), destination))
                .filter(quote -> Math.abs(quote.packageWeightLbs() - packageWeightLbs) < WEIGHT_MATCH_EPSILON)
                .findFirst();
    }

    public void evict(int userId) {
        String key = CacheKeyUtils.quoteSnapshotKey(userId);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("Failed to evict quote snapshot cache for key '{}': {}", key, e.getMessage());
        }
    }

    private boolean sameDestination(String left, String right) {
        return Objects.equals(normalizeDestination(left), normalizeDestination(right));
    }

    private String normalizeDestination(String destination) {
        if (destination == null) {
            return "";
        }
        return destination.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
