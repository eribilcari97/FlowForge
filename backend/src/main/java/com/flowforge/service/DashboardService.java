package com.flowforge.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.DashboardResponse;
import com.flowforge.dto.UpcomingRunResponse;
import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.WorkflowSchedule;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WorkflowScheduleRepository;

@Service
public class DashboardService {

    static final int ITEMS_PER_SECTION = 10;
    static final Duration FAILURE_WINDOW = Duration.ofHours(24);

    private final ExecutionService executionService;
    private final WorkflowScheduleRepository schedules;
    private final WorkflowRepository workflows;

    public DashboardService(ExecutionService executionService, WorkflowScheduleRepository schedules,
            WorkflowRepository workflows) {
        this.executionService = executionService;
        this.schedules = schedules;
        this.workflows = workflows;
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(Long ownerId) {
        Instant now = executionService.databaseNow();
        return new DashboardResponse(
                executionService.listOwned(ownerId, ExecutionStatus.RUNNING, 0, ITEMS_PER_SECTION),
                executionService.listOwnedFailedSince(ownerId, now.minus(FAILURE_WINDOW), ITEMS_PER_SECTION),
                upcomingRuns(ownerId));
    }

    private List<UpcomingRunResponse> upcomingRuns(Long ownerId) {
        List<WorkflowSchedule> upcoming = schedules.findUpcomingOwned(ownerId, PageRequest.of(0, ITEMS_PER_SECTION));
        Map<Long, String> names = new HashMap<>();
        workflows.findAllByIds(upcoming.stream().map(WorkflowSchedule::getWorkflowId).toList())
                .forEach(workflow -> names.put(workflow.getId(), workflow.getName()));
        return upcoming.stream()
                .map(schedule -> new UpcomingRunResponse(schedule.getId(), schedule.getWorkflowId(),
                        names.get(schedule.getWorkflowId()), schedule.getCronExpression(), schedule.getTimezone(),
                        schedule.getNextRunAt()))
                .toList();
    }
}
