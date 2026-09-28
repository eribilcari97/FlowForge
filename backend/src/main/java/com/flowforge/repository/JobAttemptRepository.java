package com.flowforge.repository;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.flowforge.entity.JobAttempt;

public interface JobAttemptRepository extends Repository<JobAttempt, Long> {

    List<JobAttempt> findAllByJobIdOrderByAttemptNumber(Long jobId);
}
