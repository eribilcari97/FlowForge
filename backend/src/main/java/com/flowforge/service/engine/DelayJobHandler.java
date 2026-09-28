package com.flowforge.service.engine;

import java.time.Duration;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import com.flowforge.entity.JobType;

@Component
public class DelayJobHandler implements JobHandler {

    @Override
    public JobType type() {
        return JobType.DELAY;
    }

    @Override
    public Duration readyDelay(JsonNode config) {
        return Duration.parse(config.get("duration").stringValue());
    }

    @Override
    public boolean isSafeToRepeat(JsonNode config) {
        return true;
    }

    @Override
    public JobResult execute(JsonNode config, JobContext context) {
        return new JobResult.Success(JsonNodeFactory.instance.objectNode()
                .put("waited", config.get("duration").stringValue()));
    }
}
