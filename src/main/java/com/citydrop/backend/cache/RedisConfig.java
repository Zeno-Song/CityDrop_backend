package com.citydrop.backend.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class RedisConfig {
}
