package com.flowforge.service.engine;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import com.flowforge.repository.JobQueue;
import com.flowforge.repository.JobQueue.ClaimedJob;
import com.flowforge.repository.JobQueue.ExecutionContext;

@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobQueue queue;
    private final JobHandlers handlers;
    private final ExecutionCoordinator coordinator;
    private final AttemptTimeout attemptTimeout;
    private final PlaceholderResolver resolver = new PlaceholderResolver();

    public JobRunner(JobQueue queue, JobHandlers handlers, ExecutionCoordinator coordinator,
            AttemptTimeout attemptTimeout) {
        this.queue = queue;
        this.handlers = handlers;
        this.coordinator = coordinator;
        this.attemptTimeout = attemptTimeout;
    }

    public void run(ClaimedJob job) {
        MDC.put("executionId", String.valueOf(job.executionId()));
        MDC.put("jobId", String.valueOf(job.id()));
        MDC.put("attempt", String.valueOf(job.attemptNumber()));
        try {
            JobResult result = execute(job);
            log.info("Job {} finished: {}", job.stepKey(), result instanceof JobResult.Failure failure
                    ? failure.type() + " " + failure.message()
                    : "SUCCEEDED");
            coordinator.record(job, result);
        } catch (RuntimeException e) {
            log.error("Could not record the outcome of job {}", job.stepKey(), e);
        } finally {
            MDC.clear();
        }
    }

    private JobResult execute(ClaimedJob job) {
        try {
            ExecutionContext context = queue.loadContext(job.executionId());
            JsonNode config = resolver.resolve(job.config(), new PlaceholderResolver.Context(
                    context.input(), job.executionId(), context.runNumber(), context.stepOutputs()));
            JobContext jobContext = new JobContext(job.id(), job.attemptNumber(), job.timeoutSeconds());
            JobResult result = attemptTimeout.run(job.timeoutSeconds(),
                    () -> handlers.forType(job.jobType()).execute(config, jobContext));
            if (result instanceof JobResult.Success success && isTooLarge(success.output())) {
                return new JobResult.Failure(ErrorType.OUTPUT_TOO_LARGE, "Output is larger than 256 KB");
            }
            return result;
        } catch (PlaceholderResolver.MissingValueException e) {
            return new JobResult.Failure(ErrorType.PLACEHOLDER_MISSING, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Job {} failed unexpectedly", job.stepKey(), e);
            return new JobResult.Failure(ErrorType.UNEXPECTED_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static boolean isTooLarge(JsonNode output) {
        return output != null
                && output.toString().getBytes(StandardCharsets.UTF_8).length > HttpJobHandler.MAX_OUTPUT_BYTES;
    }
}
