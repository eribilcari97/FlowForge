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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "workflow_step")
public class WorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    @Column(name = "step_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false)
    private JobType jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode config;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "retry_delay_seconds", nullable = false)
    private int retryDelaySeconds;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowStep() {
    }

    public WorkflowStep(Long workflowId, String key, JobType jobType) {
        this.workflowId = workflowId;
        this.key = key;
        this.jobType = jobType;
    }

    public void update(String name, JsonNode config, int timeoutSeconds, int maxAttempts, int retryDelaySeconds) {
        this.name = name;
        this.config = config;
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = maxAttempts;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public JobType getJobType() {
        return jobType;
    }

    public JsonNode getConfig() {
        return config;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
