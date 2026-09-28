package com.flowforge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.flowforge.entity.WorkflowSchedule;

public interface WorkflowScheduleRepository extends Repository<WorkflowSchedule, Long> {

    WorkflowSchedule saveAndFlush(WorkflowSchedule schedule);

    void delete(WorkflowSchedule schedule);

    void flush();

    List<WorkflowSchedule> findAllByWorkflowIdOrderById(Long workflowId);

    Optional<WorkflowSchedule> findByIdAndWorkflowId(Long id, Long workflowId);

    @Query("""
           select s from WorkflowSchedule s, Workflow w, Project p
           where w.id = s.workflowId and p.id = w.projectId and p.ownerId = :ownerId
             and s.enabled = true and w.status = com.flowforge.entity.WorkflowStatus.ACTIVE
           order by s.nextRunAt, s.id
           """)
    List<WorkflowSchedule> findUpcomingOwned(Long ownerId, Pageable pageable);

    @Query(value = """
                   SELECT s.* FROM workflow_schedule s JOIN workflow w ON w.id = s.workflow_id
                   WHERE s.enabled AND s.next_run_at <= now() AND w.status = 'ACTIVE'
                   ORDER BY s.next_run_at
                   LIMIT 1
                   FOR UPDATE OF s SKIP LOCKED
                   """, nativeQuery = true)
    Optional<WorkflowSchedule> lockNextDue();
}
