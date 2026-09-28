package com.flowforge.dto;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

import com.flowforge.entity.WorkflowSchedule;

public record ScheduleResponse(
        Long id,
        Long workflowId,
        String cronExpression,
        String timezone,
        JsonNode input,
        boolean enabled,
        Instant nextRunAt,
        Instant lastRunAt) {

    public static ScheduleResponse from(WorkflowSchedule schedule) {
        return new ScheduleResponse(schedule.getId(), schedule.getWorkflowId(), schedule.getCronExpression(),
                schedule.getTimezone(), schedule.getInput(), schedule.isEnabled(), schedule.getNextRunAt(),
                schedule.getLastRunAt());
    }
}
