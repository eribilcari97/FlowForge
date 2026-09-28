package com.flowforge.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

import com.flowforge.entity.JobType;
import com.flowforge.entity.WorkflowStep;

public record StepResponse(
        Long id,
        String key,
        String name,
        JobType jobType,
        JsonNode config,
        int timeoutSeconds,
        int maxAttempts,
        int retryDelaySeconds,
        List<Long> dependsOn) {

    public static StepResponse from(WorkflowStep step, List<Long> dependsOn) {
        return new StepResponse(step.getId(), step.getKey(), step.getName(), step.getJobType(), step.getConfig(),
                step.getTimeoutSeconds(), step.getMaxAttempts(), step.getRetryDelaySeconds(), dependsOn);
    }
}
