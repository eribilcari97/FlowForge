package com.flowforge.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flowforge.dto.ExecutionResponse;
import com.flowforge.dto.ExecutionSummaryResponse;
import com.flowforge.dto.PageResponse;
import com.flowforge.dto.StartExecutionRequest;
import com.flowforge.entity.ExecutionStatus;
import com.flowforge.security.CurrentUser;
import com.flowforge.service.ExecutionService;

@RestController
public class ExecutionController {

    private final ExecutionService executionService;
    private final CurrentUser currentUser;

    public ExecutionController(ExecutionService executionService, CurrentUser currentUser) {
        this.executionService = executionService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/workflows/{workflowId}/executions")
    ResponseEntity<ExecutionResponse> start(@PathVariable Long workflowId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) StartExecutionRequest request) {
        ExecutionService.StartResult result = executionService.start(workflowId, currentUser.id(),
                request != null ? request.input() : null, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/executions/" + result.execution().id()))
                .body(result.execution());
    }

    @GetMapping("/api/workflows/{workflowId}/executions")
    PageResponse<ExecutionSummaryResponse> listOfWorkflow(@PathVariable Long workflowId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return executionService.listOfWorkflow(workflowId, currentUser.id(), page, size);
    }

    @GetMapping("/api/executions")
    PageResponse<ExecutionSummaryResponse> list(@RequestParam(required = false) ExecutionStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return executionService.listOwned(currentUser.id(), status, page, size);
    }

    @GetMapping("/api/executions/{executionId}")
    ExecutionResponse get(@PathVariable Long executionId) {
        return executionService.get(executionId, currentUser.id());
    }

    @PostMapping("/api/executions/{executionId}/cancel")
    ExecutionResponse cancel(@PathVariable Long executionId) {
        return executionService.cancel(executionId, currentUser.id());
    }
}
