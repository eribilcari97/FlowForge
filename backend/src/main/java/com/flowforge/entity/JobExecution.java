package com.flowforge.entity;

import java.time.Instant;
import java.util.List;

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
@Table(name = "job_execution")
public class JobExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_execution_id", nullable = false, updatable = false)
    private Long executionId;

    @Column(name = "step_key", nullable = false, updatable = false)
    private String stepKey;

    @Column(name = "step_name", nullable = false, updatable = false)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false)
    private JobType jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private JsonNode config;

    @Column(name = "timeout_seconds", nullable = false, updatable = false)
    private int timeoutSeconds;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Column(name = "retry_delay_seconds", nullable = false, updatable = false)
    private int retryDelaySeconds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "depends_on", nullable = false, updatable = false, columnDefinition = "text[]")
    private List<String> dependsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at")
    private Instant availableAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode output;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected JobExecution() {
    }

    public JobExecution(Long executionId, WorkflowStep step, List<String> dependsOn, JobStatus status,
            Instant availableAt, Instant createdAt) {
        this.executionId = executionId;
        this.stepKey = step.getKey();
        this.stepName = step.getName();
        this.jobType = step.getJobType();
        this.config = step.getConfig().deepCopy();
        this.timeoutSeconds = step.getTimeoutSeconds();
        this.maxAttempts = step.getMaxAttempts();
        this.retryDelaySeconds = step.getRetryDelaySeconds();
        this.dependsOn = List.copyOf(dependsOn);
        this.status = status;
        this.availableAt = availableAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public String getStepKey() {
        return stepKey;
    }

    public String getStepName() {
        return stepName;
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

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public JsonNode getOutput() {
        return output;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
