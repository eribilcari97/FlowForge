package com.flowforge.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.ProjectRequest;
import com.flowforge.dto.ProjectResponse;
import com.flowforge.entity.Project;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.NotFoundException;
import com.flowforge.repository.ProjectRepository;
import com.flowforge.repository.WorkflowRepository;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final WorkflowRepository workflows;

    public ProjectService(ProjectRepository projects, WorkflowRepository workflows) {
        this.projects = projects;
        this.workflows = workflows;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(Long ownerId) {
        return projects.findAllOwnedBy(ownerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long projectId, Long ownerId) {
        return toResponse(findOwned(projectId, ownerId));
    }

    @Transactional
    public ProjectResponse create(Long ownerId, ProjectRequest request) {
        ensureNameAvailable(ownerId, request.name(), null);
        Project project = projects.saveAndFlush(new Project(ownerId, request.name(), request.description()));
        return toResponse(project);
    }

    @Transactional
    public ProjectResponse update(Long projectId, Long ownerId, ProjectRequest request) {
        Project project = findOwned(projectId, ownerId);
        ensureNameAvailable(ownerId, request.name(), project);
        project.update(request.name(), request.description());
        return toResponse(project);
    }

    @Transactional
    public void delete(Long projectId, Long ownerId) {
        Project project = findOwned(projectId, ownerId);
        if (workflows.countByProjectId(projectId) > 0) {
            throw new ConflictException(ErrorCode.PROJECT_NOT_EMPTY, "Delete the project's workflows first");
        }
        projects.delete(project);
    }

    private Project findOwned(Long projectId, Long ownerId) {
        return projects.findByIdAndOwnerId(projectId, ownerId).orElseThrow(() -> new NotFoundException("Project"));
    }

    private void ensureNameAvailable(Long ownerId, String name, Project current) {
        projects.findOwnedByName(ownerId, name)
                .filter(existing -> current == null || !existing.getId().equals(current.getId()))
                .ifPresent(existing -> {
                    throw new ConflictException(ErrorCode.DUPLICATE_NAME, "A project with this name already exists");
                });
    }

    private ProjectResponse toResponse(Project project) {
        long workflowCount = workflows.countByProjectId(project.getId());
        return new ProjectResponse(
                project.getId(), project.getName(), project.getDescription(), workflowCount, project.getCreatedAt());
    }
}
