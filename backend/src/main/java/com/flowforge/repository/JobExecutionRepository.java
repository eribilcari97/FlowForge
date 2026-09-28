package com.flowforge.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.flowforge.entity.JobExecution;
import com.flowforge.entity.JobStatus;

public interface JobExecutionRepository extends Repository<JobExecution, Long> {

    JobExecution save(JobExecution job);

    void flush();

    List<JobExecution> findAllByExecutionIdOrderById(Long executionId);

    @Query("""
           select j from JobExecution j, WorkflowExecution e, Workflow w, Project p
           where j.id = :id and e.id = j.executionId and w.id = e.workflowId and p.id = w.projectId
             and p.ownerId = :ownerId
           """)
    Optional<JobExecution> findOwned(Long id, Long ownerId);

    @Modifying
    @Query("""
           update JobExecution j set j.status = :newStatus, j.finishedAt = :now
           where j.executionId = :executionId and j.status in :currentStatuses
           """)
    int updateStatus(Long executionId, Collection<JobStatus> currentStatuses, JobStatus newStatus, Instant now);
}
