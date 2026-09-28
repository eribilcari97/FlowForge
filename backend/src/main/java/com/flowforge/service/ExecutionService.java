package com.flowforge.service;

import java.time.Duration;
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

import com.flowforge.dto.ExecutionResponse;
import com.flowforge.dto.ExecutionSummaryResponse;
import com.flowforge.dto.JobSummaryResponse;
import com.flowforge.dto.PageResponse;
import com.flowforge.dto.ValidationProblem;
import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.JobExecution;
import com.flowforge.entity.JobStatus;
import com.flowforge.entity.JobType;
import com.flowforge.entity.TriggerType;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowDependency;
import com.flowforge.entity.WorkflowExecution;
import com.flowforge.entity.WorkflowStep;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.GlobalExceptionHandler.FieldError;
import com.flowforge.exception.InvalidFieldsException;
import com.flowforge.exception.NotFoundException;
import com.flowforge.exception.WorkflowInvalidException;
import com.flowforge.repository.JobExecutionRepository;
import com.flowforge.repository.WorkflowDependencyRepository;
import com.flowforge.repository.WorkflowExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowStepRepository;

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
    private final WorkflowValidator validator;

    public ExecutionService(WorkflowRepository workflows, WorkflowStepRepository steps,
            WorkflowDependencyRepository dependencies, WorkflowExecutionRepository executions,
            JobExecutionRepository jobs, WorkflowValidator validator) {
        this.workflows = workflows;
        this.steps = steps;
        this.dependencies = dependencies;
        this.executions = executions;
        this.jobs = jobs;
        this.validator = validator;
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

        List<WorkflowStep> workflowSteps = steps.findAllByWorkflowIdOrderById(workflowId);
        List<WorkflowDependency> edges = dependencies.findAllByWorkflowId(workflowId);
        List<ValidationProblem> problems =
                validator.validate(workflowSteps, DependencyService.graphOf(workflowSteps, edges));
        if (!problems.isEmpty()) {
            throw new WorkflowInvalidException(problems);
        }

        int runNumber = workflows.nextRunNumber(workflowId);
        Instant now = executions.databaseNow();
        JsonNode executionInput = input != null ? input : JsonNodeFactory.instance.objectNode();
        WorkflowExecution execution = executions.saveAndFlush(new WorkflowExecution(
                workflowId, runNumber, TriggerType.MANUAL, userId, dedupKey, executionInput, now));

        Map<Long, List<String>> dependsOnByStep = dependencyKeysByStep(workflowSteps, edges);
        for (WorkflowStep step : workflowSteps) {
            List<String> dependsOn = dependsOnByStep.getOrDefault(step.getId(), List.of());
            boolean root = dependsOn.isEmpty();
            jobs.save(new JobExecution(execution.getId(), step, dependsOn,
                    root ? JobStatus.READY : JobStatus.PENDING,
                    root ? now.plus(readyDelay(step)) : null,
                    now));
        }
        jobs.flush();
        return new StartResult(toResponse(execution, workflow.getName()), true);
    }

    @Transactional(readOnly = true)
    public ExecutionResponse get(Long executionId, Long ownerId) {
        WorkflowExecution execution = findOwned(executionId, ownerId);
        return toResponse(execution, workflowName(execution));
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
        Set<Long> workflowIds = result.getContent().stream()
                .map(WorkflowExecution::getWorkflowId)
                .collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        workflows.findAllByIds(workflowIds).forEach(workflow -> names.put(workflow.getId(), workflow.getName()));
        return toPage(result, names, pageRequest);
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

    private static Duration readyDelay(WorkflowStep step) {
        if (step.getJobType() == JobType.DELAY) {
            return Duration.parse(step.getConfig().get("duration").stringValue());
        }
        return Duration.ZERO;
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
