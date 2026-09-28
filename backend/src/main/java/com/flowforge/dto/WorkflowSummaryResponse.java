package com.flowforge.dto;

import java.time.Instant;

import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowStatus;

public record WorkflowSummaryResponse(
        Long id,
        Long projectId,
        String name,
        String description,
        WorkflowStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkflowSummaryResponse from(Workflow workflow) {
        return new WorkflowSummaryResponse(workflow.getId(), workflow.getProjectId(), workflow.getName(),
                workflow.getDescription(), workflow.getStatus(), workflow.getCreatedAt(), workflow.getUpdatedAt());
    }
}
