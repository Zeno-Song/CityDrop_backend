package com.citydrop.backend.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "citydrop.cache")
public record CacheProperties(
        Duration quoteLockTtl,
        Duration geocodeTtl,
        Duration travelTimeTtl
) {
    public CacheProperties {
        if (quoteLockTtl == null) {
            quoteLockTtl = Duration.ofMinutes(5);
        }
        if (geocodeTtl == null) {
            geocodeTtl = Duration.ofHours(24);
        }
        if (travelTimeTtl == null) {
            travelTimeTtl = Duration.ofMinutes(15);
        }
    }
}