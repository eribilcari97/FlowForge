package com.flowforge.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.flowforge.entity.Workflow;

public interface WorkflowRepository extends Repository<Workflow, Long> {

    Workflow saveAndFlush(Workflow workflow);

    void delete(Workflow workflow);

    void flush();

    @Query("""
           select w from Workflow w, Project p
           where w.id = :id and p.id = w.projectId and p.ownerId = :ownerId
           """)
    Optional<Workflow> findOwned(Long id, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select w from Workflow w, Project p
           where w.id = :id and p.id = w.projectId and p.ownerId = :ownerId
           """)
    Optional<Workflow> findOwnedForUpdate(Long id, Long ownerId);

    @Query("select w from Workflow w where w.projectId = :projectId order by lower(w.name)")
    List<Workflow> findAllInProject(Long projectId);

    @Query("""
           select w from Workflow w
           where w.projectId = :projectId and lower(w.name) = lower(:name)
             and w.status <> com.flowforge.entity.WorkflowStatus.ARCHIVED
           """)
    Optional<Workflow> findActiveByName(Long projectId, String name);

    long countByProjectId(Long projectId);

    @Query("select w from Workflow w where w.id in :ids")
    List<Workflow> findAllByIds(Collection<Long> ids);

    @Query(value = """
                   update workflow set execution_count = execution_count + 1
                   where id = :id
                   returning execution_count
                   """, nativeQuery = true)
    int nextRunNumber(Long id);
}
