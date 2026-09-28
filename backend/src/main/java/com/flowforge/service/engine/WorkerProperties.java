package com.flowforge.service.engine;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowforge.worker")
public record WorkerProperties(boolean enabled, int threads, Duration pollInterval, Duration leaseGrace) {

    public WorkerProperties {
        if (threads < 1) {
            throw new IllegalStateException("flowforge.worker.threads must be at least 1");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalStateException("flowforge.worker.poll-interval must be positive");
        }
        if (leaseGrace == null || leaseGrace.isNegative()) {
            throw new IllegalStateException("flowforge.worker.lease-grace must not be negative");
        }
    }
}
