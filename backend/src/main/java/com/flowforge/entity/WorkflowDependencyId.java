package com.flowforge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record WorkflowDependencyId(
        @Column(name = "step_id") Long stepId,
        @Column(name = "depends_on_step_id") Long dependsOnStepId) {
}
