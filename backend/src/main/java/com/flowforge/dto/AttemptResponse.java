package com.flowforge.dto;

import java.time.Instant;

import com.flowforge.entity.AttemptStatus;
import com.flowforge.entity.JobAttempt;

public record AttemptResponse(
        int number,
        AttemptStatus status,
        String workerId,
        Instant startedAt,
        Instant finishedAt,
        String errorType,
        String errorMessage,
        Boolean retryable) {

    public static AttemptResponse from(JobAttempt attempt) {
        return new AttemptResponse(attempt.getAttemptNumber(), attempt.getStatus(), attempt.getWorkerId(),
                attempt.getStartedAt(), attempt.getFinishedAt(), attempt.getErrorType(), attempt.getErrorMessage(),
                attempt.getRetryable());
    }
}
