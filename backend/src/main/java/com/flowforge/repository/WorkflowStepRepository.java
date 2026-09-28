package com.flowforge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.flowforge.entity.WorkflowStep;

public interface WorkflowStepRepository extends Repository<WorkflowStep, Long> {

    WorkflowStep saveAndFlush(WorkflowStep step);

    void delete(WorkflowStep step);

    void flush();

    Optional<WorkflowStep> findByIdAndWorkflowId(Long id, Long workflowId);

    List<WorkflowStep> findAllByWorkflowIdOrderById(Long workflowId);

    long countByWorkflowId(Long workflowId);

    boolean existsByWorkflowIdAndKey(Long workflowId, String key);
}
