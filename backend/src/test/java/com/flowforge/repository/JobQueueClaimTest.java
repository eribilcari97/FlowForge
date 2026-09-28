package com.flowforge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flowforge.EngineTestData;
import com.flowforge.IntegrationTest;
import com.flowforge.repository.JobQueue.ClaimedJob;

@IntegrationTest
class JobQueueClaimTest {

    private static final int JOBS = 200;
    private static final int WORKERS = 8;
    private static final Duration LEASE_GRACE = Duration.ofSeconds(60);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobQueue queue;

    long executionId;

    @BeforeEach
    void setUp() {
        EngineTestData.cancelLeftoverWork(jdbc);
        long userId = jdbc.sql("""
                INSERT INTO "user" (email, password_hash, display_name)
                VALUES ('claim-' || gen_random_uuid() || '@example.com', 'x', 'Claim') RETURNING id
                """).query(Long.class).single();
        long projectId = jdbc.sql("INSERT INTO project (owner_id, name) VALUES (?, 'Claim') RETURNING id")
                .param(userId).query(Long.class).single();
        long workflowId = jdbc.sql("INSERT INTO workflow (project_id, name, status) VALUES (?, 'Claim', 'ACTIVE') RETURNING id")
                .param(projectId).query(Long.class).single();
        executionId = jdbc.sql("""
                INSERT INTO workflow_execution (workflow_id, run_number, status, trigger_type)
                VALUES (?, 1, 'RUNNING', 'MANUAL') RETURNING id
                """).param(workflowId).query(Long.class).single();
        for (int i = 0; i < JOBS; i++) {
            jdbc.sql("""
                    INSERT INTO job_execution (workflow_execution_id, step_key, step_name, job_type, config,
                                               timeout_seconds, max_attempts, retry_delay_seconds, status, available_at)
                    VALUES (?, ?, ?, 'DELAY', '{"duration": "PT1S"}', 30, 3, 10, 'READY', now() - interval '1 second')
                    """).params(executionId, "job_" + i, "job_" + i).update();
        }
    }

    @Test
    void readyJobsClaimedConcurrentlyAreEachClaimedExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<ClaimedJob>>> results = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                String workerId = "test-worker-" + w;
                results.add(executor.submit(() -> {
                    start.await();
                    List<ClaimedJob> mine = new ArrayList<>();
                    List<ClaimedJob> batch;
                    do {
                        batch = queue.claim(5, workerId, LEASE_GRACE);
                        mine.addAll(batch);
                    } while (!batch.isEmpty());
                    return mine;
                }));
            }
            start.countDown();

            List<Long> claimedIds = new ArrayList<>();
            for (Future<List<ClaimedJob>> result : results) {
                result.get().forEach(job -> claimedIds.add(job.id()));
            }

            assertThat(claimedIds).hasSize(JOBS).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.sql("""
                SELECT count(*) FROM job_execution
                WHERE workflow_execution_id = ? AND status = 'RUNNING' AND attempt_count = 1 AND locked_by IS NOT NULL
                """).param(executionId).query(Long.class).single()).isEqualTo(JOBS);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM job_attempt a JOIN job_execution j ON j.id = a.job_execution_id
                WHERE j.workflow_execution_id = ? AND a.attempt_number = 1 AND a.status = 'RUNNING'
                """).param(executionId).query(Long.class).single()).isEqualTo(JOBS);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT a.worker_id) FROM job_attempt a JOIN job_execution j ON j.id = a.job_execution_id
                WHERE j.workflow_execution_id = ?
                """).param(executionId).query(Long.class).single()).isGreaterThan(1);
    }

    @Test
    void jobsWhoseTimeHasNotComeYetAreNotClaimed() {
        jdbc.sql("UPDATE job_execution SET available_at = now() + interval '1 hour' WHERE workflow_execution_id = ?")
                .param(executionId).update();

        assertThat(queue.claim(10, "test-worker", LEASE_GRACE)).isEmpty();
    }
}
