package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class AttemptTimeoutTest {

    private final AttemptTimeout attemptTimeout = new AttemptTimeout();

    @AfterEach
    void shutDown() {
        attemptTimeout.shutdown();
    }

    @Test
    void anAttemptThatRunsTooLongIsInterruptedAndReportedAsATimeout() {
        AtomicBoolean interrupted = new AtomicBoolean();
        long startedAt = System.nanoTime();

        JobResult result = attemptTimeout.run(1, () -> {
            try {
                Thread.sleep(10_000);
                return new JobResult.Success(JsonNodeFactory.instance.objectNode());
            } catch (InterruptedException e) {
                interrupted.set(true);
                return new JobResult.Failure(ErrorType.CONNECTION_ERROR, "Request was interrupted");
            }
        });

        assertThat(result).isEqualTo(new JobResult.Failure(ErrorType.TIMEOUT, "No response within 1 s"));
        assertThat(interrupted).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anAttemptThatFinishesInTimeKeepsItsResult() throws Exception {
        JobResult success = new JobResult.Success(JsonNodeFactory.instance.objectNode().put("ok", true));

        assertThat(attemptTimeout.run(1, () -> success)).isEqualTo(success);

        Thread.sleep(1500);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anExceptionAfterTheTimeoutIsReportedAsATimeout() {
        JobResult result = attemptTimeout.run(1, () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted while waiting");
            }
            return null;
        });

        assertThat(result).isEqualTo(new JobResult.Failure(ErrorType.TIMEOUT, "No response within 1 s"));
    }
}
