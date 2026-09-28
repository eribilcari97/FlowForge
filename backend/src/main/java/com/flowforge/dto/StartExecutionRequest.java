package com.flowforge.dto;

import tools.jackson.databind.JsonNode;

public record StartExecutionRequest(JsonNode input) {
}
