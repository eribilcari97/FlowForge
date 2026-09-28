package com.flowforge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flowforge.dto.DashboardResponse;
import com.flowforge.security.CurrentUser;
import com.flowforge.service.DashboardService;

@RestController
public class DashboardController {

    private final DashboardService dashboardService;
    private final CurrentUser currentUser;

    public DashboardController(DashboardService dashboardService, CurrentUser currentUser) {
        this.dashboardService = dashboardService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/dashboard")
    DashboardResponse get() {
        return dashboardService.get(currentUser.id());
    }
}
