package com.flowforge.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.StepResponse;
import com.flowforge.dto.WorkflowRequest;
import com.flowforge.dto.WorkflowResponse;
import com.flowforge.dto.WorkflowSummaryResponse;
import com.flowforge.dto.WorkflowUpdateRequest;
import com.flowforge.entity.Workflow;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.NotFoundException;
import com.flowforge.repository.ProjectRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowStepRepository;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;
    private final ProjectRepository projects;

    public WorkflowService(WorkflowRepository workflows, WorkflowStepRepository steps, ProjectRepository projects) {
        this.workflows = workflows;
        this.steps = steps;
        this.projects = projects;
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
    public void delete(Long workflowId, Long ownerId) {
        Workflow workflow = findOwned(workflowId, ownerId);
        workflows.delete(workflow);
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
        List<StepResponse> stepResponses = steps.findAllByWorkflowIdOrderById(workflow.getId()).stream()
                .map(StepResponse::from)
                .toList();
        return WorkflowResponse.from(workflow, stepResponses);
    }
}
