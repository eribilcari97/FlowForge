package com.flowforge.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.StepCreateRequest;
import com.flowforge.dto.StepResponse;
import com.flowforge.dto.StepUpdateRequest;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowDependency;
import com.flowforge.entity.WorkflowStep;
import com.flowforge.exception.ApiException;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.NotFoundException;
import com.flowforge.repository.WorkflowDependencyRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowStepRepository;

@Service
public class StepService {

    public static final int MAX_STEPS_PER_WORKFLOW = 30;

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_DELAY_SECONDS = 10;

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;
    private final WorkflowDependencyRepository dependencies;
    private final WorkflowService workflowService;
    private final StepConfigValidator configValidator;

    public StepService(WorkflowRepository workflows, WorkflowStepRepository steps,
            WorkflowDependencyRepository dependencies, WorkflowService workflowService,
            StepConfigValidator configValidator) {
        this.workflows = workflows;
        this.steps = steps;
        this.dependencies = dependencies;
        this.workflowService = workflowService;
        this.configValidator = configValidator;
    }

    @Transactional
    public StepResponse add(Long workflowId, Long ownerId, StepCreateRequest request) {
        Workflow workflow = workflows.findOwnedForUpdate(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException("Workflow"));
        WorkflowService.ensureNotArchived(workflow);
        configValidator.validate(request.jobType(), request.config());
        if (steps.existsByWorkflowIdAndKey(workflowId, request.key())) {
            throw new ConflictException(ErrorCode.DUPLICATE_NAME, "A step with this key already exists");
        }
        if (steps.countByWorkflowId(workflowId) >= MAX_STEPS_PER_WORKFLOW) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.STEP_LIMIT_REACHED,
                    "A workflow can have at most " + MAX_STEPS_PER_WORKFLOW + " steps");
        }
        WorkflowStep step = new WorkflowStep(workflowId, request.key(), request.jobType());
        step.update(request.name(), request.config(),
                valueOrDefault(request.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS),
                valueOrDefault(request.maxAttempts(), DEFAULT_MAX_ATTEMPTS),
                valueOrDefault(request.retryDelaySeconds(), DEFAULT_RETRY_DELAY_SECONDS));
        return StepResponse.from(steps.saveAndFlush(step), List.of());
    }

    @Transactional
    public StepResponse update(Long workflowId, Long stepId, Long ownerId, StepUpdateRequest request) {
        Workflow workflow = workflowService.findOwned(workflowId, ownerId);
        WorkflowService.ensureNotArchived(workflow);
        WorkflowStep step = findStep(workflowId, stepId);
        configValidator.validate(step.getJobType(), request.config());
        step.update(request.name(), request.config(),
                valueOrDefault(request.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS),
                valueOrDefault(request.maxAttempts(), DEFAULT_MAX_ATTEMPTS),
                valueOrDefault(request.retryDelaySeconds(), DEFAULT_RETRY_DELAY_SECONDS));
        steps.flush();
        List<Long> dependsOn = dependencies.findAllOfStep(stepId).stream()
                .map(WorkflowDependency::getDependsOnStepId)
                .sorted()
                .toList();
        return StepResponse.from(step, dependsOn);
    }

    @Transactional
    public void delete(Long workflowId, Long stepId, Long ownerId) {
        Workflow workflow = workflows.findOwnedForUpdate(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException("Workflow"));
        WorkflowService.ensureNotArchived(workflow);
        WorkflowStep step = findStep(workflowId, stepId);
        List<WorkflowDependency> dependents = dependencies.findAllDependingOn(stepId);
        if (!dependents.isEmpty()) {
            String dependentKeys = dependents.stream()
                    .map(edge -> findStep(workflowId, edge.getStepId()).getKey())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new ConflictException(ErrorCode.STEP_HAS_DEPENDENTS,
                    step.getKey() + " is still required by: " + dependentKeys);
        }
        steps.delete(step);
    }

    private WorkflowStep findStep(Long workflowId, Long stepId) {
        return steps.findByIdAndWorkflowId(stepId, workflowId).orElseThrow(() -> new NotFoundException("Step"));
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
