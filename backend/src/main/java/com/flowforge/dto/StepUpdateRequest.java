package com.flowforge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record StepUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull JsonNode config,
        @Min(1) @Max(300) Integer timeoutSeconds,
        @Min(1) @Max(10) Integer maxAttempts,
        @Min(1) @Max(3600) Integer retryDelaySeconds) {
}
