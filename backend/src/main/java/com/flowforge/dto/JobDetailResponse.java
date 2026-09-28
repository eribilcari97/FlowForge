package com.flowforge.dto;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.flowforge.entity.JobExecution;
import com.flowforge.entity.JobStatus;
import com.flowforge.entity.JobType;

public record JobDetailResponse(
        Long id,
        Long executionId,
        String stepKey,
        String stepName,
        JobType jobType,
        JobStatus status,
        JsonNode config,
        int timeoutSeconds,
        int maxAttempts,
        int attemptCount,
        List<String> dependsOn,
        Instant availableAt,
        Instant startedAt,
        Instant finishedAt,
        JsonNode output,
        String lastError,
        List<AttemptResponse> attempts) {

    public static JobDetailResponse from(JobExecution job, List<AttemptResponse> attempts) {
        return new JobDetailResponse(job.getId(), job.getExecutionId(), job.getStepKey(), job.getStepName(),
                job.getJobType(), job.getStatus(), job.getConfig(), job.getTimeoutSeconds(), job.getMaxAttempts(),
                job.getAttemptCount(), job.getDependsOn(), job.getAvailableAt(), job.getStartedAt(),
                job.getFinishedAt(), job.getOutput(), job.getLastError(), attempts);
    }
}
