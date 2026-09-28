package com.flowforge.dto;

import java.util.List;

public record DependenciesResponse(Long stepId, List<Long> dependsOn) {
}
