package com.flowforge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.flowforge.entity.Project;

public interface ProjectRepository extends Repository<Project, Long> {

    Project saveAndFlush(Project project);

    void delete(Project project);

    Optional<Project> findByIdAndOwnerId(Long id, Long ownerId);

    @Query("select p from Project p where p.ownerId = :ownerId order by lower(p.name)")
    List<Project> findAllOwnedBy(Long ownerId);

    @Query("select p from Project p where p.ownerId = :ownerId and lower(p.name) = lower(:name)")
    Optional<Project> findOwnedByName(Long ownerId, String name);
}
