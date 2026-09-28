package com.flowforge.service.engine;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.entity.Workflow;
import com.flowforge.entity.WorkflowExecution;
import com.flowforge.entity.WorkflowSchedule;
import com.flowforge.exception.WorkflowInvalidException;
import com.flowforge.repository.WorkflowExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowScheduleRepository;
import com.flowforge.service.CronSchedule;
import com.flowforge.service.ExecutionService;

@Service
public class ScheduleFirer {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFirer.class);

    private final WorkflowScheduleRepository schedules;
    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final ExecutionService executionService;

    public ScheduleFirer(WorkflowScheduleRepository schedules, WorkflowRepository workflows,
            WorkflowExecutionRepository executions, ExecutionService executionService) {
        this.schedules = schedules;
        this.workflows = workflows;
        this.executions = executions;
        this.executionService = executionService;
    }

    @Transactional
    public boolean fireNextDue() {
        Optional<WorkflowSchedule> due = schedules.lockNextDue();
        if (due.isEmpty()) {
            return false;
        }
        WorkflowSchedule schedule = due.get();
        Instant dueAt = schedule.getNextRunAt();
        Workflow workflow = workflows.lockById(schedule.getWorkflowId()).orElseThrow();

        if (executionService.hasRunningExecution(workflow.getId())) {
            log.info("Skipping the run of schedule {} due at {}: workflow {} is still running",
                    schedule.getId(), dueAt, workflow.getId());
        } else {
            try {
                WorkflowExecution execution = executionService.startScheduled(workflow, schedule, dueAt);
                log.info("Schedule {} started run #{} of workflow {} (due at {})",
                        schedule.getId(), execution.getRunNumber(), workflow.getId(), dueAt);
            } catch (WorkflowInvalidException e) {
                log.warn("Skipping the run of schedule {} due at {}: workflow {} is invalid: {}",
                        schedule.getId(), dueAt, workflow.getId(), e.getProblems());
            }
        }

        Instant nextRunAt = CronSchedule.of(schedule.getCronExpression(), schedule.getTimezone())
                .nextAfter(executions.databaseNow());
        schedule.recordDueTime(dueAt, nextRunAt);
        schedules.flush();
        return true;
    }
}
