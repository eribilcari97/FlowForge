package com.flowforge.service.engine;

import static com.flowforge.EngineTestData.executionStatus;
import static com.flowforge.EngineTestData.jobStatus;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.node.JsonNodeFactory;

import com.flowforge.EngineTestData;
import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;
import com.flowforge.repository.JobQueue;
import com.flowforge.repository.JobQueue.ClaimedJob;

@IntegrationTest
class RecoveryTest {

    private static final Duration LEASE_GRACE = Duration.ofSeconds(60);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobQueue queue;

    @Autowired
    ExecutionCoordinator coordinator;

    @Autowired
    RecoveryTask recovery;

    TestApi api;
    String user;
    long projectId;

    @BeforeEach
    void setUp() {
        EngineTestData.cancelLeftoverWork(jdbc);
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Recovery");
    }

    @Test
    void aClaimHoldsALeaseForTheTimeoutPlusTheGracePeriod() {
        long execution = startSingleStep("GET", 3);

        queue.claim(1, "test-worker", LEASE_GRACE);

        Instant leaseExpiresAt = jdbc.sql("SELECT lease_expires_at FROM job_execution WHERE workflow_execution_id = ?")
                .param(execution).query(Instant.class).single();
        assertThat(Duration.between(Instant.now(), leaseExpiresAt))
                .isBetween(Duration.ofSeconds(80), Duration.ofSeconds(90));
        assertThat(recovery.recoverExpiredLeases()).isZero();
    }

    @Test
    void aSafeJobWhoseWorkerDisappearedIsRetriedAfterItsLeaseExpires() {
        long execution = startSingleStep("GET", 3);
        ClaimedJob claimed = queue.claim(1, "vanished-worker", LEASE_GRACE).getFirst();
        expireLease(execution);

        assertThat(recovery.recoverExpiredLeases()).isEqualTo(1);

        assertThat(jobStatus(jdbc, execution, "call")).isEqualTo("READY");
        assertThat(attempt(claimed)).isEqualTo("ABANDONED LEASE_EXPIRED true");
        assertThat(jdbc.sql("""
                SELECT locked_by IS NULL AND lease_expires_at IS NULL AND available_at > now()
                FROM job_execution WHERE id = ?
                """).param(claimed.id()).query(Boolean.class).single()).isTrue();
        assertThat(executionStatus(jdbc, execution)).isEqualTo("RUNNING");
    }

    @Test
    void anAbandonedPostIsNotRepeatedAndFailsWithAnUnknownOutcome() {
        long execution = startWithDownstream("POST");
        ClaimedJob claimed = queue.claim(1, "vanished-worker", LEASE_GRACE).getFirst();
        expireLease(execution);

        recovery.recoverExpiredLeases();

        assertThat(jobStatus(jdbc, execution, "call")).isEqualTo("FAILED");
        assertThat(jobStatus(jdbc, execution, "after")).isEqualTo("SKIPPED");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("FAILED");
        assertThat(attempt(claimed)).isEqualTo("ABANDONED LEASE_EXPIRED false");
        assertThat(jdbc.sql("SELECT last_error FROM job_execution WHERE id = ?").param(claimed.id())
                .query(String.class).single())
                .isEqualTo("Worker stopped responding (lease expired). "
                        + "Outcome unknown: check the target system before running again.");
    }

    @Test
    void anAbandonedJobWithoutAttemptsLeftFails() {
        long execution = startSingleStep("GET", 1);
        queue.claim(1, "vanished-worker", LEASE_GRACE);
        expireLease(execution);

        recovery.recoverExpiredLeases();

        assertThat(jobStatus(jdbc, execution, "call")).isEqualTo("FAILED");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("FAILED");
    }

    @Test
    void anAbandonedJobOfACancelledExecutionIsCancelled() {
        long execution = startSingleStep("GET", 3);
        queue.claim(1, "vanished-worker", LEASE_GRACE);
        api.post(user, "/api/executions/" + execution + "/cancel", "");
        expireLease(execution);

        recovery.recoverExpiredLeases();

        assertThat(jobStatus(jdbc, execution, "call")).isEqualTo("CANCELLED");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("CANCELLED");
    }

    @Test
    void aLateResultFromAnAbandonedAttemptIsIgnored() {
        long execution = startSingleStep("GET", 3);
        ClaimedJob firstAttempt = queue.claim(1, "slow-worker", LEASE_GRACE).getFirst();
        expireLease(execution);
        recovery.recoverExpiredLeases();
        jdbc.sql("UPDATE job_execution SET available_at = now() WHERE id = ?").param(firstAttempt.id()).update();
        ClaimedJob secondAttempt = queue.claim(1, "healthy-worker", LEASE_GRACE).getFirst();
        assertThat(secondAttempt.attemptNumber()).isEqualTo(2);

        coordinator.record(firstAttempt, new JobResult.Success(JsonNodeFactory.instance.objectNode().put("late", true)));

        assertThat(jobStatus(jdbc, execution, "call")).isEqualTo("RUNNING");
        assertThat(attempt(firstAttempt)).isEqualTo("ABANDONED LEASE_EXPIRED true");
        assertThat(jdbc.sql("SELECT error_message FROM job_attempt WHERE job_execution_id = ? AND attempt_number = 1")
                .param(firstAttempt.id()).query(String.class).single())
                .endsWith("Late result ignored: SUCCEEDED.");

        coordinator.record(secondAttempt, new JobResult.Success(JsonNodeFactory.instance.objectNode().put("ok", true)));

        assertThat(jobStatus(jdbc, execution, "call")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT output::text FROM job_execution WHERE id = ?").param(firstAttempt.id())
                .query(String.class).single()).contains("\"ok\"").doesNotContain("late");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
    }

    private long startSingleStep(String method, int maxAttempts) {
        long workflow = api.createWorkflow(user, projectId, "Single " + method + " " + maxAttempts);
        addStep(workflow, "call", method, maxAttempts);
        api.activate(user, workflow);
        return api.startExecution(user, workflow, "{}");
    }

    private long startWithDownstream(String method) {
        long workflow = api.createWorkflow(user, projectId, "With downstream");
        long call = addStep(workflow, "call", method, 3);
        long after = addStep(workflow, "after", "GET", 3);
        api.setDependencies(user, workflow, after, call);
        api.activate(user, workflow);
        return api.startExecution(user, workflow, "{}");
    }

    private long addStep(long workflow, String key, String method, int maxAttempts) {
        var result = api.post(user, "/api/workflows/" + workflow + "/steps", """
                { "key": "%s", "name": "%s", "jobType": "HTTP",
                  "config": { "method": "%s", "url": "https://example.com/%s" },
                  "timeoutSeconds": 30, "maxAttempts": %d, "retryDelaySeconds": 10 }
                """.formatted(key, key, method, key, maxAttempts));
        assertThat(result).hasStatus(201);
        return TestApi.idOf(result);
    }

    private void expireLease(long execution) {
        jdbc.sql("""
                UPDATE job_execution SET lease_expires_at = now() - interval '1 second'
                WHERE workflow_execution_id = ? AND status = 'RUNNING'
                """).param(execution).update();
    }

    private String attempt(ClaimedJob job) {
        return jdbc.sql("""
                SELECT status || ' ' || coalesce(error_type, 'null') || ' ' || coalesce(retryable::text, 'null')
                FROM job_attempt WHERE job_execution_id = ? AND attempt_number = ?
                """).params(job.id(), job.attemptNumber()).query(String.class).single();
    }
}
