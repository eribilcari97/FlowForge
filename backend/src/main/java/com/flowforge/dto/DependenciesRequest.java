package com.flowforge.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

public record DependenciesRequest(@NotNull List<@NotNull Long> dependsOn) {
}
