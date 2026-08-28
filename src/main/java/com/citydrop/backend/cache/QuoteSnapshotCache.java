package com.citydrop.backend.cache;

import com.citydrop.backend.cache.QuoteSnapshot.TimedQuote;
import com.citydrop.backend.models.responses.DeliveryQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    // Merges freshly computed quotes into whatever this user already has locked in,
    // instead of replacing the entry outright -- a re-fetch for a different destination
    // (a second tab, comparing addresses, editing the form) used to silently evict an
    // earlier, still-valid quote and make it unsubmittable with a misleading
    // QuoteExpiredException. Each quote gets its own expiry, so an old entry that's
    // still within its lock window survives a newer, unrelated fetch; a re-fetch of the
    // SAME (station, vehicle, destination, weight) replaces just that entry with the
    // fresher price rather than piling up duplicates.
    public void put(int userId, List<DeliveryQuote> quotes) {
        String key = CacheKeyUtils.quoteSnapshotKey(userId);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(cacheProperties.quoteLockTtl());

        Map<String, TimedQuote> merged = new LinkedHashMap<>();
        for (TimedQuote existing : readEntries(key)) {
            if (existing.expiresAt().isAfter(now)) {
                merged.put(identity(existing.quote()), existing);
            }
        }
        for (DeliveryQuote quote : quotes) {
            merged.put(identity(quote), new TimedQuote(quote, expiresAt));
        }

        QuoteSnapshot snapshot = new QuoteSnapshot(userId, List.copyOf(merged.values()));
        try {
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(snapshot),
                    cacheProperties.quoteLockTtl()
            );
        } catch (RuntimeException e) {
            log.warn("Failed to write quote snapshot cache for key '{}': {}", key, e.getMessage());
            throw new QuoteCacheUnavailableException("Failed to write quote snapshot cache for key '" + key + "'", e);
        }
    }

    public QuoteSnapshot get(int userId) {
        Instant now = Instant.now();
        List<TimedQuote> active = readEntries(CacheKeyUtils.quoteSnapshotKey(userId)).stream()
                .filter(t -> t.expiresAt().isAfter(now))
                .toList();
        return active.isEmpty() ? null : new QuoteSnapshot(userId, active);
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
                .map(TimedQuote::quote)
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

    private List<TimedQuote> readEntries(String key) {
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Failed to read quote snapshot cache for key '{}': {}", key, e.getMessage());
            throw new QuoteCacheUnavailableException("Failed to read quote snapshot cache for key '" + key + "'", e);
        }
        if (cached == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(cached, QuoteSnapshot.class).quotes();
        } catch (RuntimeException e) {
            log.warn("Failed to parse quote snapshot cache for key '{}': {}", key, e.getMessage());
            throw new QuoteCacheUnavailableException("Failed to parse quote snapshot cache for key '" + key + "'", e);
        }
    }

    // Identifies "the same quote slot" across fetches -- a re-fetch that returns a quote
    // for this exact combination replaces the old one instead of coexisting with it, so
    // findMatching always prefers the freshest price for a repeated ask.
    private String identity(DeliveryQuote quote) {
        return quote.stationId() + "|" + quote.vehicle().toUpperCase(Locale.ROOT)
                + "|" + normalizeDestination(quote.destination())
                + "|" + quote.packageWeightLbs();
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
