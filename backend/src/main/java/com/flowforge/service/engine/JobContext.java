package com.flowforge.service.engine;

import tools.jackson.databind.JsonNode;

public record JobContext(long jobId, int attemptNumber, int timeoutSeconds, JsonNode data) {
}
