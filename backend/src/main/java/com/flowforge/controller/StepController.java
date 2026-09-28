package com.flowforge.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.flowforge.dto.StepCreateRequest;
import com.flowforge.dto.StepResponse;
import com.flowforge.dto.StepUpdateRequest;
import com.flowforge.security.CurrentUser;
import com.flowforge.service.StepService;

@RestController
@RequestMapping("/api/workflows/{workflowId}/steps")
public class StepController {

    private final StepService stepService;
    private final CurrentUser currentUser;

    public StepController(StepService stepService, CurrentUser currentUser) {
        this.stepService = stepService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StepResponse add(@PathVariable Long workflowId, @Valid @RequestBody StepCreateRequest request) {
        return stepService.add(workflowId, currentUser.id(), request);
    }

    @PutMapping("/{stepId}")
    StepResponse update(@PathVariable Long workflowId, @PathVariable Long stepId,
            @Valid @RequestBody StepUpdateRequest request) {
        return stepService.update(workflowId, stepId, currentUser.id(), request);
    }

    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long workflowId, @PathVariable Long stepId) {
        stepService.delete(workflowId, stepId, currentUser.id());
    }
}
