package com.flowforge.service.engine;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowforge.scheduler")
public record SchedulerProperties(boolean enabled, Duration interval, int maxRunsPerTick) {

    public SchedulerProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalStateException("flowforge.scheduler.interval must be positive");
        }
        if (maxRunsPerTick < 1) {
            throw new IllegalStateException("flowforge.scheduler.max-runs-per-tick must be at least 1");
        }
    }
}
