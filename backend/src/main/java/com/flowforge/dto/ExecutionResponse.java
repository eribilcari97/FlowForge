package com.flowforge.dto;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.TriggerType;
import com.flowforge.entity.WorkflowExecution;

public record ExecutionResponse(
        Long id,
        Long workflowId,
        String workflowName,
        int runNumber,
        ExecutionStatus status,
        TriggerType triggerType,
        JsonNode input,
        Instant createdAt,
        Instant finishedAt,
        String errorSummary,
        List<JobSummaryResponse> jobs) {

    public static ExecutionResponse from(WorkflowExecution execution, String workflowName,
            List<JobSummaryResponse> jobs) {
        return new ExecutionResponse(execution.getId(), execution.getWorkflowId(), workflowName,
                execution.getRunNumber(), execution.getStatus(), execution.getTriggerType(), execution.getInput(),
                execution.getCreatedAt(), execution.getFinishedAt(), execution.getErrorSummary(), jobs);
    }
}
