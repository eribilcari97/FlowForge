package com.flowforge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import com.flowforge.entity.JobType;

public record StepCreateRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,49}$") String key,
        @NotBlank @Size(max = 100) String name,
        @NotNull JobType jobType,
        @NotNull JsonNode config,
        @Min(1) @Max(300) Integer timeoutSeconds,
        @Min(1) @Max(10) Integer maxAttempts,
        @Min(1) @Max(3600) Integer retryDelaySeconds) {
}
