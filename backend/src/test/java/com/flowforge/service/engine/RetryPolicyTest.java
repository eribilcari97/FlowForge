package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RetryPolicyTest {

    @Test
    void theDelayDoublesAfterEachAttempt() {
        assertThat(RetryPolicy.delayAfter(1, 10)).isEqualTo(Duration.ofSeconds(10));
        assertThat(RetryPolicy.delayAfter(2, 10)).isEqualTo(Duration.ofSeconds(20));
        assertThat(RetryPolicy.delayAfter(3, 10)).isEqualTo(Duration.ofSeconds(40));
        assertThat(RetryPolicy.delayAfter(4, 1)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void theDelayIsCappedAtTenMinutes() {
        assertThat(RetryPolicy.delayAfter(7, 10)).isEqualTo(Duration.ofMinutes(10));
        assertThat(RetryPolicy.delayAfter(10, 3600)).isEqualTo(Duration.ofMinutes(10));
        assertThat(RetryPolicy.delayAfter(1000, 10)).isEqualTo(Duration.ofMinutes(10));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorType.class, names = { "CONNECTION_ERROR", "HTTP_5XX", "HTTP_429", "EMAIL_DEFERRED", "UNEXPECTED_ERROR" })
    void transientErrorsAreRetriedEvenForUnsafeSteps(ErrorType type) {
        assertThat(RetryPolicy.isRetryable(type, false)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorType.class, names = { "TIMEOUT", "LEASE_EXPIRED", "OUTCOME_UNKNOWN" })
    void anUnknownOutcomeIsRetriedOnlyWhenTheStepIsSafeToRepeat(ErrorType type) {
        assertThat(RetryPolicy.isRetryable(type, true)).isTrue();
        assertThat(RetryPolicy.isRetryable(type, false)).isFalse();
        assertThat(RetryPolicy.isOutcomeUnknown(type)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorType.class,
            names = { "HTTP_4XX", "UNEXPECTED_STATUS", "INVALID_CONFIG", "PLACEHOLDER_MISSING", "OUTPUT_TOO_LARGE",
                    "TRANSFORM_ERROR", "EMAIL_REJECTED" })
    void errorsThatWouldHappenAgainAreNeverRetried(ErrorType type) {
        assertThat(RetryPolicy.isRetryable(type, true)).isFalse();
        assertThat(RetryPolicy.isOutcomeUnknown(type)).isFalse();
    }
}
