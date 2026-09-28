package com.flowforge.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.flowforge.entity.WorkflowDependency;
import com.flowforge.entity.WorkflowDependencyId;

public interface WorkflowDependencyRepository extends Repository<WorkflowDependency, WorkflowDependencyId> {

    WorkflowDependency save(WorkflowDependency dependency);

    void delete(WorkflowDependency dependency);

    void flush();

    List<WorkflowDependency> findAllByWorkflowId(Long workflowId);

    @Query("select d from WorkflowDependency d where d.id.stepId = :stepId")
    List<WorkflowDependency> findAllOfStep(Long stepId);

    @Query("select d from WorkflowDependency d where d.id.dependsOnStepId = :stepId")
    List<WorkflowDependency> findAllDependingOn(Long stepId);
}
