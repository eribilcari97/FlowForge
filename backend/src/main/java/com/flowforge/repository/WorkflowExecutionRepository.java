package com.flowforge.repository;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.WorkflowExecution;

public interface WorkflowExecutionRepository extends Repository<WorkflowExecution, Long> {

    WorkflowExecution saveAndFlush(WorkflowExecution execution);

    void flush();

    @Query(value = "select now()", nativeQuery = true)
    Instant databaseNow();

    Optional<WorkflowExecution> findByWorkflowIdAndDedupKey(Long workflowId, String dedupKey);

    boolean existsByWorkflowId(Long workflowId);

    @Query("""
           select e from WorkflowExecution e, Workflow w, Project p
           where e.id = :id and w.id = e.workflowId and p.id = w.projectId and p.ownerId = :ownerId
           """)
    Optional<WorkflowExecution> findOwned(Long id, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WorkflowExecution e where e.id = :id")
    Optional<WorkflowExecution> lockById(Long id);

    @Query("select e from WorkflowExecution e where e.workflowId = :workflowId order by e.createdAt desc, e.id desc")
    Page<WorkflowExecution> findPageOfWorkflow(Long workflowId, Pageable pageable);

    @Query(value = """
                   select e from WorkflowExecution e, Workflow w, Project p
                   where w.id = e.workflowId and p.id = w.projectId and p.ownerId = :ownerId
                   order by e.createdAt desc, e.id desc
                   """,
           countQuery = """
                   select count(e) from WorkflowExecution e, Workflow w, Project p
                   where w.id = e.workflowId and p.id = w.projectId and p.ownerId = :ownerId
                   """)
    Page<WorkflowExecution> findPageOwned(Long ownerId, Pageable pageable);

    @Query(value = """
                   select e from WorkflowExecution e, Workflow w, Project p
                   where w.id = e.workflowId and p.id = w.projectId and p.ownerId = :ownerId
                     and e.status = :status
                   order by e.createdAt desc, e.id desc
                   """,
           countQuery = """
                   select count(e) from WorkflowExecution e, Workflow w, Project p
                   where w.id = e.workflowId and p.id = w.projectId and p.ownerId = :ownerId
                     and e.status = :status
                   """)
    Page<WorkflowExecution> findPageOwnedWithStatus(Long ownerId, ExecutionStatus status, Pageable pageable);
}
