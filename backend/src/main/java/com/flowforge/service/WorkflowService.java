package com.flowforge.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.StepResponse;
import com.flowforge.dto.ValidationProblem;
import com.flowforge.dto.WorkflowRequest;
import com.flowforge.dto.WorkflowResponse;
import com.flowforge.dto.WorkflowSummaryResponse;
import com.flowforge.dto.WorkflowUpdateRequest;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowDependency;
import com.flowforge.entity.WorkflowStep;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.NotFoundException;
import com.flowforge.exception.WorkflowInvalidException;
import com.flowforge.repository.ProjectRepository;
import com.flowforge.repository.WorkflowDependencyRepository;
import com.flowforge.repository.WorkflowExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowStepRepository;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;
    private final WorkflowDependencyRepository dependencies;
    private final WorkflowExecutionRepository executions;
    private final ProjectRepository projects;
    private final WorkflowValidator validator;

    public WorkflowService(WorkflowRepository workflows, WorkflowStepRepository steps,
            WorkflowDependencyRepository dependencies, WorkflowExecutionRepository executions,
            ProjectRepository projects, WorkflowValidator validator) {
        this.workflows = workflows;
        this.steps = steps;
        this.dependencies = dependencies;
        this.executions = executions;
        this.projects = projects;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummaryResponse> list(Long projectId, Long ownerId) {
        ensureProjectOwned(projectId, ownerId);
        return workflows.findAllInProject(projectId).stream().map(WorkflowSummaryResponse::from).toList();
    }

    @Transactional
    public WorkflowResponse create(Long projectId, Long ownerId, WorkflowRequest request) {
        ensureProjectOwned(projectId, ownerId);
        ensureNameAvailable(projectId, request.name(), null);
        Workflow workflow = workflows.saveAndFlush(new Workflow(projectId, request.name(), request.description()));
        return WorkflowResponse.from(workflow, List.of());
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(Long workflowId, Long ownerId) {
        Workflow workflow = findOwned(workflowId, ownerId);
        return toResponse(workflow);
    }

    @Transactional
    public WorkflowResponse update(Long workflowId, Long ownerId, WorkflowUpdateRequest request) {
        Workflow workflow = findOwned(workflowId, ownerId);
        ensureNotArchived(workflow);
        if (!workflow.getVersion().equals(request.version())) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT, "The workflow was changed by someone else");
        }
        ensureNameAvailable(workflow.getProjectId(), request.name(), workflow);
        workflow.update(request.name(), request.description());
        workflows.flush();
        return toResponse(workflow);
    }

    @Transactional
    public WorkflowResponse activate(Long workflowId, Long ownerId) {
        Workflow workflow = workflows.findOwnedForUpdate(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException("Workflow"));
        ensureNotArchived(workflow);
        List<WorkflowStep> workflowSteps = steps.findAllByWorkflowIdOrderById(workflowId);
        List<WorkflowDependency> edges = dependencies.findAllByWorkflowId(workflowId);
        List<ValidationProblem> problems =
                validator.validate(workflowSteps, DependencyService.graphOf(workflowSteps, edges));
        if (!problems.isEmpty()) {
            throw new WorkflowInvalidException(problems);
        }
        workflow.activate();
        workflows.flush();
        return toResponse(workflow);
    }

    @Transactional
    public WorkflowResponse deactivate(Long workflowId, Long ownerId) {
        Workflow workflow = findOwned(workflowId, ownerId);
        ensureNotArchived(workflow);
        workflow.deactivate();
        workflows.flush();
        return toResponse(workflow);
    }

    @Transactional
    public void delete(Long workflowId, Long ownerId) {
        Workflow workflow = findOwned(workflowId, ownerId);
        if (executions.existsByWorkflowId(workflowId)) {
            workflow.archive();
        } else {
            workflows.delete(workflow);
        }
    }

    Workflow findOwned(Long workflowId, Long ownerId) {
        return workflows.findOwned(workflowId, ownerId).orElseThrow(() -> new NotFoundException("Workflow"));
    }

    static void ensureNotArchived(Workflow workflow) {
        if (workflow.isArchived()) {
            throw new ConflictException(ErrorCode.INVALID_STATE, "Archived workflows can't be changed");
        }
    }

    private void ensureProjectOwned(Long projectId, Long ownerId) {
        if (projects.findByIdAndOwnerId(projectId, ownerId).isEmpty()) {
            throw new NotFoundException("Project");
        }
    }

    private void ensureNameAvailable(Long projectId, String name, Workflow current) {
        workflows.findActiveByName(projectId, name)
                .filter(existing -> current == null || !existing.getId().equals(current.getId()))
                .ifPresent(existing -> {
                    throw new ConflictException(ErrorCode.DUPLICATE_NAME, "A workflow with this name already exists");
                });
    }

    private WorkflowResponse toResponse(Workflow workflow) {
        Map<Long, List<Long>> dependsOnByStep = dependencies.findAllByWorkflowId(workflow.getId()).stream()
                .collect(Collectors.groupingBy(WorkflowDependency::getStepId,
                        Collectors.mapping(WorkflowDependency::getDependsOnStepId, Collectors.toList())));
        List<StepResponse> stepResponses = steps.findAllByWorkflowIdOrderById(workflow.getId()).stream()
                .map(step -> StepResponse.from(step,
                        dependsOnByStep.getOrDefault(step.getId(), List.of()).stream().sorted().toList()))
                .toList();
        return WorkflowResponse.from(workflow, stepResponses);
    }
}
