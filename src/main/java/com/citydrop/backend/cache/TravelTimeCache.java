package com.citydrop.backend.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class TravelTimeCache {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeCache.class);

    private final StringRedisTemplate redisTemplate;
    private final CacheProperties cacheProperties;

    public TravelTimeCache(StringRedisTemplate redisTemplate, CacheProperties cacheProperties) {
        this.redisTemplate = redisTemplate;
        this.cacheProperties = cacheProperties;
    }

    public double getOrLoad(
            int stationId,
            double destCoordX,
            double destCoordY,
            String vehicle,
            Supplier<Double> loader
    ) {
        String key = CacheKeyUtils.travelTimeKey(stationId, destCoordX, destCoordY, vehicle);

        Double cached = readCached(key);
        if (cached != null) {
            return cached;
        }

        double loaded = loader.get(); // TimeEstimationFailureException propagates uncaught, nothing cached
        writeCache(key, loaded);
        return loaded;
    }

    private Double readCached(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? null : Double.valueOf(value);
        } catch (RuntimeException e) {
            log.warn("Failed to read travel-time cache for key '{}': {}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, double timeMinutes) {
        try {
            redisTemplate.opsForValue().set(key, Double.toString(timeMinutes), cacheProperties.travelTimeTtl());
        } catch (RuntimeException e) {
            log.warn("Failed to write travel-time cache for key '{}': {}", key, e.getMessage());
        }
    }
}