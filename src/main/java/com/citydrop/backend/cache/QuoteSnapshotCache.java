package com.citydrop.backend.cache;

import com.citydrop.backend.cache.QuoteSnapshot.TimedQuote;
import com.citydrop.backend.models.responses.DeliveryQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class QuoteSnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotCache.class);
    private static final double WEIGHT_MATCH_EPSILON = 0.0001;

    // Must comfortably exceed how long a put() body ever takes, otherwise the lock can
    // expire out from under a still-running holder and let a second caller in -- which
    // reintroduces the exact lost-update race this lock exists to prevent.
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);
    // Must exceed LOCK_TTL: a waiter should never give up before a crashed holder's lock
    // would have naturally expired.
    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration LOCK_RETRY_DELAY = Duration.ofMillis(25);

    // Only deletes the lock if it still holds this caller's token, so a caller whose lock
    // already expired (and was possibly reacquired by someone else) can't delete a lock
    // it no longer owns.
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

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
    /** LIMITATION: this is a timed lock, so if a cache SET takes longer than locked time, the lock will be removed. But
     *  for this app, with working Redis servers, this is very likely a safe implementation */
    public void put(int userId, List<DeliveryQuote> quotes) {
        String key = CacheKeyUtils.quoteSnapshotKey(userId);
        String lockKey = key + ":lock";
        String token = acquireLock(lockKey);
        try {
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
        } finally {
            releaseLock(lockKey, token);
        }
    }

    private String acquireLock(String lockKey) {
        String token = UUID.randomUUID().toString();
        Instant deadline = Instant.now().plus(LOCK_WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return token;
            }
            try {
                Thread.sleep(LOCK_RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QuoteCacheUnavailableException("Interrupted waiting for quote cache lock '" + lockKey + "'", e);
            }
        }
        throw new QuoteCacheUnavailableException("Timed out waiting for quote cache lock '" + lockKey + "'", null);
    }

    private void releaseLock(String lockKey, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("Failed to release quote cache lock '{}': {}", lockKey, e.getMessage());
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
