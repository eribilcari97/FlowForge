package com.flowforge.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.flowforge.dto.ScheduleRequest;
import com.flowforge.dto.ScheduleResponse;
import com.flowforge.security.CurrentUser;
import com.flowforge.service.ScheduleService;

@RestController
@RequestMapping("/api/workflows/{workflowId}/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final CurrentUser currentUser;

    public ScheduleController(ScheduleService scheduleService, CurrentUser currentUser) {
        this.scheduleService = scheduleService;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<ScheduleResponse> list(@PathVariable Long workflowId) {
        return scheduleService.list(workflowId, currentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ScheduleResponse create(@PathVariable Long workflowId, @Valid @RequestBody ScheduleRequest request) {
        return scheduleService.create(workflowId, currentUser.id(), request);
    }

    @PutMapping("/{scheduleId}")
    ScheduleResponse update(@PathVariable Long workflowId, @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleRequest request) {
        return scheduleService.update(workflowId, scheduleId, currentUser.id(), request);
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long workflowId, @PathVariable Long scheduleId) {
        scheduleService.delete(workflowId, scheduleId, currentUser.id());
    }
}
