package com.flowforge.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "job_attempt")
public class JobAttempt {

    @Id
    private Long id;

    @Column(name = "job_execution_id")
    private Long jobId;

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    private AttemptStatus status;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_type")
    private String errorType;

    @Column(name = "error_message")
    private String errorMessage;

    private Boolean retryable;

    protected JobAttempt() {
    }

    public Long getId() {
        return id;
    }

    public Long getJobId() {
        return jobId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Boolean getRetryable() {
        return retryable;
    }
}
