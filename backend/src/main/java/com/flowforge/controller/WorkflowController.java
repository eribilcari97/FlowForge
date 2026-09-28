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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.flowforge.dto.WorkflowRequest;
import com.flowforge.dto.WorkflowResponse;
import com.flowforge.dto.WorkflowSummaryResponse;
import com.flowforge.dto.WorkflowUpdateRequest;
import com.flowforge.security.CurrentUser;
import com.flowforge.service.WorkflowService;

@RestController
public class WorkflowController {

    private final WorkflowService workflowService;
    private final CurrentUser currentUser;

    public WorkflowController(WorkflowService workflowService, CurrentUser currentUser) {
        this.workflowService = workflowService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/projects/{projectId}/workflows")
    List<WorkflowSummaryResponse> list(@PathVariable Long projectId) {
        return workflowService.list(projectId, currentUser.id());
    }

    @PostMapping("/api/projects/{projectId}/workflows")
    ResponseEntity<WorkflowResponse> create(@PathVariable Long projectId, @Valid @RequestBody WorkflowRequest request) {
        WorkflowResponse workflow = workflowService.create(projectId, currentUser.id(), request);
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.id())).body(workflow);
    }

    @GetMapping("/api/workflows/{workflowId}")
    WorkflowResponse get(@PathVariable Long workflowId) {
        return workflowService.get(workflowId, currentUser.id());
    }

    @PutMapping("/api/workflows/{workflowId}")
    WorkflowResponse update(@PathVariable Long workflowId, @Valid @RequestBody WorkflowUpdateRequest request) {
        return workflowService.update(workflowId, currentUser.id(), request);
    }

    @DeleteMapping("/api/workflows/{workflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long workflowId) {
        workflowService.delete(workflowId, currentUser.id());
    }
}
