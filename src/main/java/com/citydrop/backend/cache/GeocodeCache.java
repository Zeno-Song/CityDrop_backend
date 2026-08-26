package com.citydrop.backend.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class GeocodeCache {

    private static final Logger log = LoggerFactory.getLogger(GeocodeCache.class);
    private static final String SEPARATOR = ",";

    private final StringRedisTemplate redisTemplate;
    private final CacheProperties cacheProperties;

    public GeocodeCache(StringRedisTemplate redisTemplate, CacheProperties cacheProperties) {
        this.redisTemplate = redisTemplate;
        this.cacheProperties = cacheProperties;
    }

    public double[] getOrLoad(String address, Supplier<double[]> loader) {
        String key = CacheKeyUtils.geocodeKey(address);

        double[] cached = readCached(key);
        if (cached != null) {
            return cached;
        }

        double[] loaded = loader.get(); // AddressCannotBeGeocodedException propagates uncaught, nothing cached
        writeCache(key, loaded);
        return loaded;
    }

    private double[] readCached(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            String[] parts = value.split(SEPARATOR);
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (RuntimeException e) {
            log.warn("Failed to read geocode cache for key '{}': {}", key, e.getMessage());
            return null; // fail open -> caller falls through to the loader
        }
    }

    private void writeCache(String key, double[] coords) {
        try {
            redisTemplate.opsForValue().set(
                    key,
                    coords[0] + SEPARATOR + coords[1],
                    cacheProperties.geocodeTtl()
            );
        } catch (RuntimeException e) {
            log.warn("Failed to write geocode cache for key '{}': {}", key, e.getMessage());
        }
    }
}