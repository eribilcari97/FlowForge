package com.flowforge.dto;

import java.time.Instant;

public record UpcomingRunResponse(
        Long scheduleId,
        Long workflowId,
        String workflowName,
        String cronExpression,
        String timezone,
        Instant nextRunAt) {
}
