package com.flowforge.dto;

import java.util.List;

public record DashboardResponse(
        PageResponse<ExecutionSummaryResponse> running,
        PageResponse<ExecutionSummaryResponse> failedLast24Hours,
        List<UpcomingRunResponse> upcomingRuns) {
}
