package com.flowforge.service.engine;

import tools.jackson.databind.JsonNode;

public sealed interface JobResult {

    record Success(JsonNode output) implements JobResult {
    }

    record Failure(ErrorType type, String message) implements JobResult {
    }
}
