package com.flowforge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow_dependency")
public class WorkflowDependency {

    @EmbeddedId
    private WorkflowDependencyId id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    protected WorkflowDependency() {
    }

    public WorkflowDependency(Long workflowId, Long stepId, Long dependsOnStepId) {
        this.id = new WorkflowDependencyId(stepId, dependsOnStepId);
        this.workflowId = workflowId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public Long getStepId() {
        return id.stepId();
    }

    public Long getDependsOnStepId() {
        return id.dependsOnStepId();
    }
}
