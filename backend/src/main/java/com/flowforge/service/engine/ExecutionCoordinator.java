package com.flowforge.service.engine;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String OUTCOME_UNKNOWN =
            "Outcome unknown: check the target system before running again.";
    private static final String WORKER_STOPPED = "Worker stopped responding (lease expired).";

    private final JobQueue queue;
    private final JobHandlers handlers;

    public ExecutionCoordinator(JobQueue queue, JobHandlers handlers) {
        this.queue = queue;
        this.handlers = handlers;
    }

    @Transactional
    public void record(ClaimedJob job, JobResult result) {
        AttemptStatus attemptStatus = result instanceof JobResult.Success
                ? AttemptStatus.SUCCEEDED
                : AttemptStatus.FAILED;
        recordOutcome(job, result, attemptStatus);
    }

    @Transactional
    public void recordAbandoned(ClaimedJob job) {
        recordOutcome(job, new JobResult.Failure(ErrorType.LEASE_EXPIRED, WORKER_STOPPED), AttemptStatus.ABANDONED);
    }

    private void recordOutcome(ClaimedJob job, JobResult result, AttemptStatus attemptStatus) {
        ExecutionStatus executionStatus = queue.lockExecution(job.executionId());

        if (result instanceof JobResult.Success success) {
            JobStatus jobStatus = executionStatus == ExecutionStatus.CANCELLED
                    ? JobStatus.CANCELLED
                    : JobStatus.SUCCEEDED;
            if (!queue.finishJob(job.id(), job.attemptNumber(), jobStatus, success.output(), null)) {
                ignoreLateResult(job, "SUCCEEDED");
                return;
            }
            queue.finishAttempt(job.id(), job.attemptNumber(), attemptStatus, null, null, null);
            if (executionStatus == ExecutionStatus.RUNNING) {
                releaseDependents(job);
                finishExecutionIfDone(job.executionId());
            }
            return;
        }

        JobResult.Failure failure = (JobResult.Failure) result;
        boolean retryable = RetryPolicy.isRetryable(failure.type(),
                handlers.forType(job.jobType()).isSafeToRepeat(job.config()));
        boolean attemptsLeft = job.attemptNumber() < job.maxAttempts();

        boolean recorded;
        if (executionStatus == ExecutionStatus.CANCELLED) {
            recorded = queue.finishJob(job.id(), job.attemptNumber(), JobStatus.CANCELLED, null, failure.message());
        } else if (retryable && attemptsLeft) {
            Duration delay = RetryPolicy.delayAfter(job.attemptNumber(), job.retryDelaySeconds());
            recorded = queue.retryLater(job.id(), job.attemptNumber(), delay, failure.message());
        } else {
            recorded = queue.finishJob(job.id(), job.attemptNumber(), JobStatus.FAILED, null,
                    finalErrorMessage(failure, retryable));
        }
        if (!recorded) {
            ignoreLateResult(job, failure.type().name());
            return;
        }
        queue.finishAttempt(job.id(), job.attemptNumber(), attemptStatus, failure.type().name(), failure.message(),
                retryable);

        boolean failedForGood = executionStatus == ExecutionStatus.RUNNING && !(retryable && attemptsLeft);
        if (failedForGood) {
            skipDownstream(job);
            finishExecutionIfDone(job.executionId());
        }
    }

    private static String finalErrorMessage(JobResult.Failure failure, boolean retryable) {
        if (!retryable && RetryPolicy.isOutcomeUnknown(failure.type())) {
            return failure.message() + " " + OUTCOME_UNKNOWN;
        }
        return failure.message();
    }

    private void ignoreLateResult(ClaimedJob job, String result) {
        log.warn("Ignoring the result of attempt {} of job {} because the job has moved on",
                job.attemptNumber(), job.stepKey());
        queue.noteLateResult(job.id(), job.attemptNumber(), "Late result ignored: " + result + ".");
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
                .map(failed -> failed.attemptCount() > 1
                        ? failed.stepKey() + " failed after " + failed.attemptCount() + " attempts: " + failed.lastError()
                        : failed.stepKey() + " failed: " + failed.lastError())
                .orElse("A job failed");
        if (summary.length() > MAX_ERROR_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_ERROR_SUMMARY_LENGTH);
        }
        queue.finishExecution(executionId, ExecutionStatus.FAILED, summary);
    }
}
