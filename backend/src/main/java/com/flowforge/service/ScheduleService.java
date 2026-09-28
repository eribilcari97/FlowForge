package com.flowforge.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import com.flowforge.dto.ScheduleRequest;
import com.flowforge.dto.ScheduleResponse;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowSchedule;
import com.flowforge.exception.GlobalExceptionHandler.FieldError;
import com.flowforge.exception.InvalidFieldsException;
import com.flowforge.exception.NotFoundException;
import com.flowforge.repository.WorkflowExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowScheduleRepository;

@Service
public class ScheduleService {

    private final WorkflowRepository workflows;
    private final WorkflowScheduleRepository schedules;
    private final WorkflowExecutionRepository executions;

    public ScheduleService(WorkflowRepository workflows, WorkflowScheduleRepository schedules,
            WorkflowExecutionRepository executions) {
        this.workflows = workflows;
        this.schedules = schedules;
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> list(Long workflowId, Long ownerId) {
        findWorkflow(workflowId, ownerId);
        return schedules.findAllByWorkflowIdOrderById(workflowId).stream().map(ScheduleResponse::from).toList();
    }

    @Transactional
    public ScheduleResponse create(Long workflowId, Long ownerId, ScheduleRequest request) {
        Workflow workflow = findWorkflow(workflowId, ownerId);
        WorkflowService.ensureNotArchived(workflow);
        validate(request);
        WorkflowSchedule schedule = new WorkflowSchedule(workflowId);
        apply(schedule, request);
        return ScheduleResponse.from(schedules.saveAndFlush(schedule));
    }

    @Transactional
    public ScheduleResponse update(Long workflowId, Long scheduleId, Long ownerId, ScheduleRequest request) {
        Workflow workflow = findWorkflow(workflowId, ownerId);
        WorkflowService.ensureNotArchived(workflow);
        WorkflowSchedule schedule = findSchedule(workflowId, scheduleId);
        validate(request);
        apply(schedule, request);
        schedules.flush();
        return ScheduleResponse.from(schedule);
    }

    @Transactional
    public void delete(Long workflowId, Long scheduleId, Long ownerId) {
        Workflow workflow = findWorkflow(workflowId, ownerId);
        WorkflowService.ensureNotArchived(workflow);
        schedules.delete(findSchedule(workflowId, scheduleId));
    }

    private void apply(WorkflowSchedule schedule, ScheduleRequest request) {
        boolean enabled = request.enabled() == null || request.enabled();
        JsonNode input = request.input() != null ? request.input() : JsonNodeFactory.instance.objectNode();
        CronSchedule cron = CronSchedule.of(request.cronExpression(), request.timezone());
        schedule.update(request.cronExpression().trim(), request.timezone(), input, enabled,
                cron.nextAfter(executions.databaseNow()));
    }

    private static void validate(ScheduleRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (!CronSchedule.isValidCron(request.cronExpression())) {
            errors.add(new FieldError("cronExpression",
                    "must be a valid cron expression with 5 fields: minute hour day month weekday"));
        }
        if (!CronSchedule.isValidZone(request.timezone())) {
            errors.add(new FieldError("timezone", "must be an IANA time zone such as Europe/Berlin"));
        }
        if (request.input() != null && !request.input().isObject()) {
            errors.add(new FieldError("input", "must be a JSON object"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private Workflow findWorkflow(Long workflowId, Long ownerId) {
        return workflows.findOwned(workflowId, ownerId).orElseThrow(() -> new NotFoundException("Workflow"));
    }

    private WorkflowSchedule findSchedule(Long workflowId, Long scheduleId) {
        return schedules.findByIdAndWorkflowId(scheduleId, workflowId)
                .orElseThrow(() -> new NotFoundException("Schedule"));
    }
}
