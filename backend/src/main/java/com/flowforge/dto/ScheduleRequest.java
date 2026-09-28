package com.flowforge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record ScheduleRequest(
        @NotBlank @Size(max = 100) String cronExpression,
        @NotBlank @Size(max = 64) String timezone,
        JsonNode input,
        Boolean enabled) {
}
