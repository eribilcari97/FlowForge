package com.flowforge.dto;

import jakarta.validation.constraints.NotBlank;

public record DelayStepConfig(@NotBlank String duration) {
}
