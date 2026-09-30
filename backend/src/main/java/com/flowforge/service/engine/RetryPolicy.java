package com.flowforge.service.engine;

import java.time.Duration;

public final class RetryPolicy {

    public static final Duration MAX_DELAY = Duration.ofMinutes(10);

    private RetryPolicy() {
    }

    public static Duration delayAfter(int attemptNumber, int retryDelaySeconds) {
        long seconds = retryDelaySeconds;
        for (int i = 1; i < attemptNumber && seconds < MAX_DELAY.toSeconds(); i++) {
            seconds *= 2;
        }
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));
    }

    public static boolean isRetryable(ErrorType errorType, boolean safeToRepeat) {
        return switch (errorType) {
            case CONNECTION_ERROR, HTTP_5XX, HTTP_429, EMAIL_DEFERRED, UNEXPECTED_ERROR -> true;
            case TIMEOUT, LEASE_EXPIRED, OUTCOME_UNKNOWN -> safeToRepeat;
            case HTTP_4XX, UNEXPECTED_STATUS, INVALID_CONFIG, BLOCKED_ADDRESS, PLACEHOLDER_MISSING, OUTPUT_TOO_LARGE, TRANSFORM_ERROR,
                    EMAIL_REJECTED -> false;
        };
    }

    public static boolean isOutcomeUnknown(ErrorType errorType) {
        return errorType == ErrorType.TIMEOUT || errorType == ErrorType.LEASE_EXPIRED
                || errorType == ErrorType.OUTCOME_UNKNOWN;
    }
}
