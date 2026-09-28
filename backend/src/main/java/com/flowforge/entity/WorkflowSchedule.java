package com.flowforge.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "workflow_schedule")
public class WorkflowSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(nullable = false)
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode input;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowSchedule() {
    }

    public WorkflowSchedule(Long workflowId) {
        this.workflowId = workflowId;
    }

    public void update(String cronExpression, String timezone, JsonNode input, boolean enabled, Instant nextRunAt) {
        this.cronExpression = cronExpression;
        this.timezone = timezone;
        this.input = input;
        this.enabled = enabled;
        this.nextRunAt = enabled ? nextRunAt : null;
    }

    public void recordDueTime(Instant dueAt, Instant nextRunAt) {
        this.lastRunAt = dueAt;
        this.nextRunAt = nextRunAt;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public String getTimezone() {
        return timezone;
    }

    public JsonNode getInput() {
        return input;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
