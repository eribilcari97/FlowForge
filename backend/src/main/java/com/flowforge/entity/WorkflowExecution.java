package com.flowforge.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "workflow_execution")
public class WorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    @Column(name = "run_number", nullable = false, updatable = false)
    private int runNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false)
    private TriggerType triggerType;

    @Column(name = "triggered_by", updatable = false)
    private Long triggeredBy;

    @Column(name = "dedup_key", updatable = false)
    private String dedupKey;

    @Column(name = "schedule_id", updatable = false)
    private Long scheduleId;

    @Column(name = "scheduled_for", updatable = false)
    private Instant scheduledFor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private JsonNode input;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected WorkflowExecution() {
    }

    public WorkflowExecution(Long workflowId, int runNumber, TriggerType triggerType, Long triggeredBy,
            Long scheduleId, Instant scheduledFor, String dedupKey, JsonNode input, Instant createdAt) {
        this.workflowId = workflowId;
        this.runNumber = runNumber;
        this.status = ExecutionStatus.RUNNING;
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.scheduleId = scheduleId;
        this.scheduledFor = scheduledFor;
        this.dedupKey = dedupKey;
        this.input = input;
        this.createdAt = createdAt;
    }

    public void cancel(Instant now) {
        status = ExecutionStatus.CANCELLED;
        finishedAt = now;
    }

    public boolean isRunning() {
        return status == ExecutionStatus.RUNNING;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public int getRunNumber() {
        return runNumber;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public Long getTriggeredBy() {
        return triggeredBy;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public JsonNode getInput() {
        return input;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
