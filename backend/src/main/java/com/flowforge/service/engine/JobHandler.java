package com.flowforge.service.engine;

import java.time.Duration;

import tools.jackson.databind.JsonNode;

import com.flowforge.entity.JobType;

public interface JobHandler {

    JobType type();

    default Duration readyDelay(JsonNode config) {
        return Duration.ZERO;
    }

    boolean isSafeToRepeat(JsonNode config);

    JobResult execute(JsonNode config, JobContext context);
}
