package com.flowforge.dto;

import java.time.Instant;
import java.util.List;

import com.flowforge.entity.JobExecution;
import com.flowforge.entity.JobStatus;
import com.flowforge.entity.JobType;

public record JobSummaryResponse(
        Long id,
        String stepKey,
        JobType jobType,
        JobStatus status,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        String lastError,
        List<String> dependsOn,
        Instant startedAt,
        Instant finishedAt) {

    public static JobSummaryResponse from(JobExecution job) {
        return new JobSummaryResponse(job.getId(), job.getStepKey(), job.getJobType(), job.getStatus(),
                job.getAttemptCount(), job.getMaxAttempts(), job.getAvailableAt(), job.getLastError(),
                job.getDependsOn(), job.getStartedAt(), job.getFinishedAt());
    }
}
