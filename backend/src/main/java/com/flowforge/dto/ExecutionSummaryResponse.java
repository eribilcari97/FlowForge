package com.flowforge.dto;

import java.time.Instant;

import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.TriggerType;
import com.flowforge.entity.WorkflowExecution;

public record ExecutionSummaryResponse(
        Long id,
        Long workflowId,
        String workflowName,
        int runNumber,
        ExecutionStatus status,
        TriggerType triggerType,
        Instant createdAt,
        Instant finishedAt,
        String errorSummary) {

    public static ExecutionSummaryResponse from(WorkflowExecution execution, String workflowName) {
        return new ExecutionSummaryResponse(execution.getId(), execution.getWorkflowId(), workflowName,
                execution.getRunNumber(), execution.getStatus(), execution.getTriggerType(),
                execution.getCreatedAt(), execution.getFinishedAt(), execution.getErrorSummary());
    }
}
