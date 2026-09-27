package com.flowforge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class SmokeTestController {

    public record ApiHealth(String status, String message) {
    }

    @GetMapping("/api/health")
    ApiHealth health() {
        return new ApiHealth("UP", "FlowForge backend is running");
    }
}
