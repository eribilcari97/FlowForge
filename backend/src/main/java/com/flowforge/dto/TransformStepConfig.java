package com.flowforge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransformStepConfig(@NotBlank @Size(max = 10_000) String expression) {
}
