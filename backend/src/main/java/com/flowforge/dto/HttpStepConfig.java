package com.flowforge.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record HttpStepConfig(
        @NotNull Method method,
        @NotBlank String url,
        Map<String, String> headers,
        JsonNode body,
        List<@NotNull @Min(100) @Max(599) Integer> expectedStatus) {

    public enum Method {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE
    }
}
