package com.flowforge.dto;

import java.time.Instant;
import java.util.List;

import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowStatus;

public record WorkflowResponse(
        Long id,
        Long projectId,
        String name,
        String description,
        WorkflowStatus status,
        Long version,
        List<StepResponse> steps,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkflowResponse from(Workflow workflow, List<StepResponse> steps) {
        return new WorkflowResponse(workflow.getId(), workflow.getProjectId(), workflow.getName(),
                workflow.getDescription(), workflow.getStatus(), workflow.getVersion(), steps,
                workflow.getCreatedAt(), workflow.getUpdatedAt());
    }
}
