package com.flowforge.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.DependenciesRequest;
import com.flowforge.dto.DependenciesResponse;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowDependency;
import com.flowforge.entity.WorkflowStep;
import com.flowforge.exception.DependencyCycleException;
import com.flowforge.exception.GlobalExceptionHandler.FieldError;
import com.flowforge.exception.InvalidFieldsException;
import com.flowforge.exception.NotFoundException;
import com.flowforge.repository.WorkflowDependencyRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowStepRepository;

@Service
public class DependencyService {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;
    private final WorkflowDependencyRepository dependencies;

    public DependencyService(WorkflowRepository workflows, WorkflowStepRepository steps,
            WorkflowDependencyRepository dependencies) {
        this.workflows = workflows;
        this.steps = steps;
        this.dependencies = dependencies;
    }

    @Transactional
    public DependenciesResponse replace(Long workflowId, Long stepId, Long ownerId, DependenciesRequest request) {
        Workflow workflow = workflows.findOwnedForUpdate(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException("Workflow"));
        WorkflowService.ensureNotArchived(workflow);

        List<WorkflowStep> workflowSteps = steps.findAllByWorkflowIdOrderById(workflowId);
        WorkflowStep step = workflowSteps.stream()
                .filter(candidate -> candidate.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Step"));
        Set<Long> requested = new LinkedHashSet<>(request.dependsOn());
        validateReferences(stepId, requested, workflowSteps);

        List<WorkflowDependency> allEdges = dependencies.findAllByWorkflowId(workflowId);
        List<WorkflowDependency> proposedEdges = new ArrayList<>(allEdges);
        proposedEdges.removeIf(edge -> edge.getStepId().equals(stepId));
        requested.forEach(dependsOnId -> proposedEdges.add(new WorkflowDependency(workflowId, stepId, dependsOnId)));
        graphOf(workflowSteps, proposedEdges).findCycleThrough(step.getKey()).ifPresent(cycle -> {
            throw new DependencyCycleException(cycle);
        });

        Set<Long> current = new LinkedHashSet<>();
        for (WorkflowDependency edge : allEdges) {
            if (edge.getStepId().equals(stepId)) {
                current.add(edge.getDependsOnStepId());
                if (!requested.contains(edge.getDependsOnStepId())) {
                    dependencies.delete(edge);
                }
            }
        }
        for (Long dependsOnId : requested) {
            if (!current.contains(dependsOnId)) {
                dependencies.save(new WorkflowDependency(workflowId, stepId, dependsOnId));
            }
        }
        dependencies.flush();
        return new DependenciesResponse(stepId, requested.stream().sorted().toList());
    }

    static WorkflowGraph graphOf(List<WorkflowStep> workflowSteps, List<WorkflowDependency> edges) {
        Map<Long, String> keysById = new HashMap<>();
        Map<String, List<String>> dependenciesByKey = new LinkedHashMap<>();
        for (WorkflowStep step : workflowSteps) {
            keysById.put(step.getId(), step.getKey());
            dependenciesByKey.put(step.getKey(), new ArrayList<>());
        }
        for (WorkflowDependency edge : edges) {
            dependenciesByKey.get(keysById.get(edge.getStepId())).add(keysById.get(edge.getDependsOnStepId()));
        }
        return new WorkflowGraph(dependenciesByKey);
    }

    private static void validateReferences(Long stepId, Set<Long> requested, List<WorkflowStep> workflowSteps) {
        Set<Long> stepIds = new LinkedHashSet<>();
        workflowSteps.forEach(step -> stepIds.add(step.getId()));
        List<FieldError> errors = new ArrayList<>();
        for (Long dependsOnId : requested) {
            if (dependsOnId.equals(stepId)) {
                errors.add(new FieldError("dependsOn", "a step can't depend on itself"));
            } else if (!stepIds.contains(dependsOnId)) {
                errors.add(new FieldError("dependsOn", "step " + dependsOnId + " is not part of this workflow"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }
}
