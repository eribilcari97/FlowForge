package com.flowforge.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import com.flowforge.dto.AttemptResponse;
import com.flowforge.dto.ExecutionResponse;
import com.flowforge.dto.ExecutionSummaryResponse;
import com.flowforge.dto.JobDetailResponse;
import com.flowforge.dto.JobSummaryResponse;
import com.flowforge.dto.PageResponse;
import com.flowforge.dto.ValidationProblem;
import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.JobExecution;
import com.flowforge.entity.JobStatus;
import com.flowforge.entity.TriggerType;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowDependency;
import com.flowforge.entity.WorkflowExecution;
import com.flowforge.entity.WorkflowSchedule;
import com.flowforge.entity.WorkflowStep;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.GlobalExceptionHandler.FieldError;
import com.flowforge.exception.InvalidFieldsException;
import com.flowforge.exception.NotFoundException;
import com.flowforge.exception.WorkflowInvalidException;
import com.flowforge.repository.JobAttemptRepository;
import com.flowforge.repository.JobExecutionRepository;
import com.flowforge.repository.WorkflowDependencyRepository;
import com.flowforge.repository.WorkflowExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowStepRepository;
import com.flowforge.service.engine.JobHandlers;

@Service
public class ExecutionService {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;

    public record StartResult(ExecutionResponse execution, boolean created) {
    }

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;
    private final WorkflowDependencyRepository dependencies;
    private final WorkflowExecutionRepository executions;
    private final JobExecutionRepository jobs;
    private final JobAttemptRepository attempts;
    private final WorkflowValidator validator;
    private final JobHandlers handlers;

    public ExecutionService(WorkflowRepository workflows, WorkflowStepRepository steps,
            WorkflowDependencyRepository dependencies, WorkflowExecutionRepository executions,
            JobExecutionRepository jobs, JobAttemptRepository attempts, WorkflowValidator validator,
            JobHandlers handlers) {
        this.workflows = workflows;
        this.steps = steps;
        this.dependencies = dependencies;
        this.executions = executions;
        this.jobs = jobs;
        this.attempts = attempts;
        this.validator = validator;
        this.handlers = handlers;
    }

    @Transactional
    public StartResult start(Long workflowId, Long userId, JsonNode input, String idempotencyKey) {
        validateRequest(input, idempotencyKey);
        Workflow workflow = workflows.findOwnedForUpdate(workflowId, userId)
                .orElseThrow(() -> new NotFoundException("Workflow"));

        String dedupKey = idempotencyKey == null ? null : "manual:" + idempotencyKey;
        if (dedupKey != null) {
            var existing = executions.findByWorkflowIdAndDedupKey(workflowId, dedupKey);
            if (existing.isPresent()) {
                return new StartResult(toResponse(existing.get(), workflow.getName()), false);
            }
        }
        if (!workflow.isActive()) {
            throw new ConflictException(ErrorCode.INVALID_STATE, "Only active workflows can be run");
        }
        JsonNode executionInput = input != null ? input : JsonNodeFactory.instance.objectNode();
        WorkflowExecution execution = createExecution(workflow, TriggerType.MANUAL, userId, null, null, dedupKey,
                executionInput);
        return new StartResult(toResponse(execution, workflow.getName()), true);
    }

    public WorkflowExecution startScheduled(Workflow lockedWorkflow, WorkflowSchedule schedule, Instant dueAt) {
        return createExecution(lockedWorkflow, TriggerType.SCHEDULE, null, schedule.getId(), dueAt,
                "schedule:" + schedule.getId() + ":" + dueAt, schedule.getInput());
    }

    public boolean hasRunningExecution(Long workflowId) {
        return executions.existsByWorkflowIdAndStatus(workflowId, ExecutionStatus.RUNNING);
    }

    private WorkflowExecution createExecution(Workflow workflow, TriggerType triggerType, Long triggeredBy,
            Long scheduleId, Instant scheduledFor, String dedupKey, JsonNode input) {
        List<WorkflowStep> workflowSteps = steps.findAllByWorkflowIdOrderById(workflow.getId());
        List<WorkflowDependency> edges = dependencies.findAllByWorkflowId(workflow.getId());
        List<ValidationProblem> problems =
                validator.validate(workflowSteps, DependencyService.graphOf(workflowSteps, edges));
        if (!problems.isEmpty()) {
            throw new WorkflowInvalidException(problems);
        }

        int runNumber = workflows.nextRunNumber(workflow.getId());
        Instant now = executions.databaseNow();
        WorkflowExecution execution = executions.saveAndFlush(new WorkflowExecution(workflow.getId(), runNumber,
                triggerType, triggeredBy, scheduleId, scheduledFor, dedupKey, input, now));

        Map<Long, List<String>> dependsOnByStep = dependencyKeysByStep(workflowSteps, edges);
        for (WorkflowStep step : workflowSteps) {
            List<String> dependsOn = dependsOnByStep.getOrDefault(step.getId(), List.of());
            boolean root = dependsOn.isEmpty();
            jobs.save(new JobExecution(execution.getId(), step, dependsOn,
                    root ? JobStatus.READY : JobStatus.PENDING,
                    root ? now.plus(handlers.forType(step.getJobType()).readyDelay(step.getConfig())) : null,
                    now));
        }
        jobs.flush();
        return execution;
    }

    @Transactional(readOnly = true)
    public ExecutionResponse get(Long executionId, Long ownerId) {
        WorkflowExecution execution = findOwned(executionId, ownerId);
        return toResponse(execution, workflowName(execution));
    }

    @Transactional(readOnly = true)
    public JobDetailResponse getJob(Long jobId, Long ownerId) {
        JobExecution job = jobs.findOwned(jobId, ownerId).orElseThrow(() -> new NotFoundException("Job"));
        List<AttemptResponse> attemptResponses = attempts.findAllByJobIdOrderByAttemptNumber(jobId).stream()
                .map(AttemptResponse::from)
                .toList();
        return JobDetailResponse.from(job, attemptResponses);
    }

    @Transactional
    public ExecutionResponse cancel(Long executionId, Long ownerId) {
        findOwned(executionId, ownerId);
        WorkflowExecution execution = executions.lockById(executionId)
                .orElseThrow(() -> new NotFoundException("Execution"));
        if (!execution.isRunning()) {
            throw new ConflictException(ErrorCode.INVALID_STATE, "Only running executions can be cancelled");
        }
        Instant now = executions.databaseNow();
        execution.cancel(now);
        executions.flush();
        jobs.updateStatus(executionId, Set.of(JobStatus.PENDING, JobStatus.READY), JobStatus.CANCELLED, now);
        return toResponse(execution, workflowName(execution));
    }

    @Transactional(readOnly = true)
    public PageResponse<ExecutionSummaryResponse> listOfWorkflow(Long workflowId, Long ownerId, int page, int size) {
        Workflow workflow = workflows.findOwned(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException("Workflow"));
        PageRequest pageRequest = pageRequest(page, size);
        Page<WorkflowExecution> result = executions.findPageOfWorkflow(workflowId, pageRequest);
        return toPage(result, Map.of(workflow.getId(), workflow.getName()), pageRequest);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExecutionSummaryResponse> listOwned(Long ownerId, ExecutionStatus status, int page,
            int size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<WorkflowExecution> result = status == null
                ? executions.findPageOwned(ownerId, pageRequest)
                : executions.findPageOwnedWithStatus(ownerId, status, pageRequest);
        return toPage(result, workflowNames(result), pageRequest);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExecutionSummaryResponse> listOwnedFailedSince(Long ownerId, Instant since, int size) {
        PageRequest pageRequest = pageRequest(0, size);
        Page<WorkflowExecution> result = executions.findPageOwnedFailedSince(ownerId, since, pageRequest);
        return toPage(result, workflowNames(result), pageRequest);
    }

    public Instant databaseNow() {
        return executions.databaseNow();
    }

    private Map<Long, String> workflowNames(Page<WorkflowExecution> page) {
        Set<Long> workflowIds = page.getContent().stream()
                .map(WorkflowExecution::getWorkflowId)
                .collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        workflows.findAllByIds(workflowIds).forEach(workflow -> names.put(workflow.getId(), workflow.getName()));
        return names;
    }

    private WorkflowExecution findOwned(Long executionId, Long ownerId) {
        return executions.findOwned(executionId, ownerId).orElseThrow(() -> new NotFoundException("Execution"));
    }

    private String workflowName(WorkflowExecution execution) {
        return workflows.findAllByIds(List.of(execution.getWorkflowId())).getFirst().getName();
    }

    private ExecutionResponse toResponse(WorkflowExecution execution, String workflowName) {
        List<JobSummaryResponse> jobResponses = jobs.findAllByExecutionIdOrderById(execution.getId()).stream()
                .map(JobSummaryResponse::from)
                .toList();
        return ExecutionResponse.from(execution, workflowName, jobResponses);
    }

    private static PageResponse<ExecutionSummaryResponse> toPage(Page<WorkflowExecution> result,
            Map<Long, String> workflowNames, PageRequest pageRequest) {
        List<ExecutionSummaryResponse> items = result.getContent().stream()
                .map(execution -> ExecutionSummaryResponse.from(execution,
                        workflowNames.get(execution.getWorkflowId())))
                .toList();
        return new PageResponse<>(items, pageRequest.getPageNumber(), pageRequest.getPageSize(),
                result.getTotalElements());
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    private static Map<Long, List<String>> dependencyKeysByStep(List<WorkflowStep> workflowSteps,
            List<WorkflowDependency> edges) {
        Map<Long, String> keysById = new HashMap<>();
        workflowSteps.forEach(step -> keysById.put(step.getId(), step.getKey()));
        Map<Long, List<String>> result = new HashMap<>();
        for (WorkflowDependency edge : edges) {
            result.computeIfAbsent(edge.getStepId(), id -> new ArrayList<>())
                    .add(keysById.get(edge.getDependsOnStepId()));
        }
        result.values().forEach(keys -> keys.sort(null));
        return result;
    }

    private static void validateRequest(JsonNode input, String idempotencyKey) {
        List<FieldError> errors = new ArrayList<>();
        if (input != null && !input.isObject()) {
            errors.add(new FieldError("input", "must be a JSON object"));
        }
        if (idempotencyKey != null
                && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
            errors.add(new FieldError("Idempotency-Key",
                    "must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }
}
