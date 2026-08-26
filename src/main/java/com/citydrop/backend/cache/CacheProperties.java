package com.citydrop.backend.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "citydrop.cache")
public record CacheProperties(
        Duration quoteLockTtl
) {
    public CacheProperties {
        if (quoteLockTtl == null) {
            quoteLockTtl = Duration.ofMinutes(5);
        }
    }
}
