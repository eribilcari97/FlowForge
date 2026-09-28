package com.flowforge.service.engine;

import java.time.Duration;

import tools.jackson.databind.JsonNode;

import com.flowforge.entity.JobType;

public interface JobHandler {

    JobType type();

    default Duration readyDelay(JsonNode config) {
        return Duration.ZERO;
    }

    JobResult execute(JsonNode config, int timeoutSeconds);
}
