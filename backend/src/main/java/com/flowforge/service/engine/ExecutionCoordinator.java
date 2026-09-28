package com.flowforge.service.engine;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import com.flowforge.entity.AttemptStatus;
import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.JobStatus;
import com.flowforge.repository.JobQueue;
import com.flowforge.repository.JobQueue.ClaimedJob;
import com.flowforge.repository.JobQueue.DependentJob;
import com.flowforge.repository.JobQueue.JobCounts;

@Service
public class ExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCoordinator.class);
    private static final int MAX_ERROR_SUMMARY_LENGTH = 1000;

    private final JobQueue queue;
    private final JobHandlers handlers;

    public ExecutionCoordinator(JobQueue queue, JobHandlers handlers) {
        this.queue = queue;
        this.handlers = handlers;
    }

    @Transactional
    public void record(ClaimedJob job, JobResult result) {
        ExecutionStatus executionStatus = queue.lockExecution(job.executionId());
        boolean succeeded = result instanceof JobResult.Success;
        JsonNode output = result instanceof JobResult.Success success ? success.output() : null;
        JobResult.Failure failure = result instanceof JobResult.Failure f ? f : null;

        JobStatus jobStatus;
        if (executionStatus == ExecutionStatus.CANCELLED) {
            jobStatus = JobStatus.CANCELLED;
        } else {
            jobStatus = succeeded ? JobStatus.SUCCEEDED : JobStatus.FAILED;
        }
        boolean recorded = queue.finishJob(job.id(), job.attemptNumber(), jobStatus, output,
                failure != null ? failure.message() : null);
        if (!recorded) {
            log.warn("Ignoring the result of attempt {} because the job has moved on", job.attemptNumber());
            return;
        }
        queue.finishAttempt(job.id(), job.attemptNumber(),
                succeeded ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED,
                failure != null ? failure.type().name() : null,
                failure != null ? failure.message() : null);

        if (executionStatus != ExecutionStatus.RUNNING) {
            return;
        }
        if (succeeded) {
            releaseDependents(job);
        } else {
            skipDownstream(job);
        }
        finishExecutionIfDone(job.executionId());
    }

    private void releaseDependents(ClaimedJob job) {
        for (DependentJob dependent : queue.pendingDependentsOf(job.executionId(), job.stepKey())) {
            if (queue.allSucceeded(job.executionId(), dependent.dependsOn())) {
                queue.markReady(dependent.id(), handlers.forType(dependent.jobType()).readyDelay(dependent.config()));
            }
        }
    }

    private void skipDownstream(ClaimedJob job) {
        List<String> failedOrSkipped = List.of(job.stepKey());
        while (!failedOrSkipped.isEmpty()) {
            failedOrSkipped = queue.skipPendingDependentsOf(job.executionId(), Set.copyOf(failedOrSkipped));
        }
    }

    private void finishExecutionIfDone(long executionId) {
        JobCounts counts = queue.countJobs(executionId);
        if (counts.active() > 0) {
            return;
        }
        if (counts.failed() == 0) {
            queue.finishExecution(executionId, ExecutionStatus.SUCCEEDED, null);
            return;
        }
        String summary = queue.firstFailedJob(executionId)
                .map(failed -> failed.stepKey() + " failed: " + failed.lastError())
                .orElse("A job failed");
        if (summary.length() > MAX_ERROR_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_ERROR_SUMMARY_LENGTH);
        }
        queue.finishExecution(executionId, ExecutionStatus.FAILED, summary);
    }
}
