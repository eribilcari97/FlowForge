package com.flowforge.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.flowforge.dto.ProjectRequest;
import com.flowforge.dto.ProjectResponse;
import com.flowforge.security.CurrentUser;
import com.flowforge.service.ProjectService;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projectService, CurrentUser currentUser) {
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<ProjectResponse> list() {
        return projectService.list(currentUser.id());
    }

    @PostMapping
    ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse project = projectService.create(currentUser.id(), request);
        return ResponseEntity.created(URI.create("/api/projects/" + project.id())).body(project);
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(@PathVariable Long projectId) {
        return projectService.get(projectId, currentUser.id());
    }

    @PutMapping("/{projectId}")
    ProjectResponse update(@PathVariable Long projectId, @Valid @RequestBody ProjectRequest request) {
        return projectService.update(projectId, currentUser.id(), request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long projectId) {
        projectService.delete(projectId, currentUser.id());
    }
}
