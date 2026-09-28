package com.flowforge.service.engine;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowforge.recovery")
public record RecoveryProperties(boolean enabled, Duration interval, int batchSize) {

    public RecoveryProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalStateException("flowforge.recovery.interval must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalStateException("flowforge.recovery.batch-size must be at least 1");
        }
    }
}
