package com.flowforge.service.engine;

import static com.flowforge.EngineTestData.executionStatus;
import static com.flowforge.EngineTestData.jobStatus;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
class ExecutionCoordinatorTest {

    private static final String HTTP_CONFIG = """
            { "method": "GET", "url": "https://example.com" }
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobQueue queue;

    @Autowired
    ExecutionCoordinator coordinator;

    TestApi api;
    String user;
    long projectId;

    @BeforeEach
    void setUp() {
        EngineTestData.cancelLeftoverWork(jdbc);
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Engine");
    }

    @Test
    void twoDependenciesFinishingAtTheSameInstantReleaseTheDependentJob() throws Exception {
        long workflow = api.createWorkflow(user, projectId, "Join");
        long b = api.addStep(user, workflow, "b", "HTTP", HTTP_CONFIG);
        long c = api.addStep(user, workflow, "c", "HTTP", HTTP_CONFIG);
        long d = api.addStep(user, workflow, "d", "HTTP", HTTP_CONFIG);
        api.setDependencies(user, workflow, d, b, c);
        api.activate(user, workflow);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                long execution = api.startExecution(user, workflow, "{}");
                List<ClaimedJob> claimed = queue.claim(2, "test-worker");
                assertThat(claimed).extracting(ClaimedJob::stepKey).containsExactlyInAnyOrder("b", "c");

                CyclicBarrier together = new CyclicBarrier(2);
                List<Future<Void>> recordings = new ArrayList<>();
                for (ClaimedJob job : claimed) {
                    recordings.add(executor.submit(() -> {
                        together.await();
                        coordinator.record(job, success());
                        return null;
                    }));
                }
                for (Future<Void> recording : recordings) {
                    recording.get();
                }

                assertThat(jobStatus(jdbc, execution, "d")).as("round %d", round).isEqualTo("READY");
                assertThat(executionStatus(jdbc, execution)).isEqualTo("RUNNING");
                EngineTestData.cancelLeftoverWork(jdbc);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aFailedJobSkipsEverythingDownstreamWhileIndependentBranchesContinue() {
        long workflow = api.createWorkflow(user, projectId, "Branches");
        long a = api.addStep(user, workflow, "a", "HTTP", HTTP_CONFIG);
        long b = api.addStep(user, workflow, "b", "HTTP", HTTP_CONFIG);
        long c = api.addStep(user, workflow, "c", "HTTP", HTTP_CONFIG);
        api.addStep(user, workflow, "x", "HTTP", HTTP_CONFIG);
        api.setDependencies(user, workflow, b, a);
        api.setDependencies(user, workflow, c, b);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");
        List<ClaimedJob> claimed = queue.claim(10, "test-worker");

        coordinator.record(job(claimed, "a"), new JobResult.Failure(ErrorType.HTTP_5XX, "HTTP 503 Service Unavailable"));

        assertThat(jobStatus(jdbc, execution, "a")).isEqualTo("FAILED");
        assertThat(jobStatus(jdbc, execution, "b")).isEqualTo("SKIPPED");
        assertThat(jobStatus(jdbc, execution, "c")).isEqualTo("SKIPPED");
        assertThat(jobStatus(jdbc, execution, "x")).isEqualTo("RUNNING");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("RUNNING");

        coordinator.record(job(claimed, "x"), success());

        assertThat(jobStatus(jdbc, execution, "x")).isEqualTo("SUCCEEDED");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT error_summary FROM workflow_execution WHERE id = ?").param(execution)
                .query(String.class).single()).isEqualTo("a failed: HTTP 503 Service Unavailable");
        assertThat(jdbc.sql("""
                SELECT a.status || ' ' || a.error_type FROM job_attempt a JOIN job_execution j ON j.id = a.job_execution_id
                WHERE j.workflow_execution_id = ? AND j.step_key = 'a'
                """).param(execution).query(String.class).single()).isEqualTo("FAILED HTTP_5XX");
    }

    @Test
    void theLastSuccessfulJobCompletesTheExecution() {
        long workflow = api.createWorkflow(user, projectId, "Single");
        api.addStep(user, workflow, "only", "HTTP", HTTP_CONFIG);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");
        ClaimedJob only = queue.claim(1, "test-worker").getFirst();

        coordinator.record(only, success());

        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT output::text FROM job_execution WHERE id = ?").param(only.id())
                .query(String.class).single()).contains("\"status\": 200");
        assertThat(jdbc.sql("SELECT finished_at IS NOT NULL FROM workflow_execution WHERE id = ?").param(execution)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void aReleasedDelayJobBecomesAvailableAfterItsDuration() {
        long workflow = api.createWorkflow(user, projectId, "Delay");
        long first = api.addStep(user, workflow, "first", "HTTP", HTTP_CONFIG);
        long wait = api.addStep(user, workflow, "wait", "DELAY", """
                { "duration": "PT10M" }
                """);
        api.setDependencies(user, workflow, wait, first);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");

        coordinator.record(queue.claim(1, "test-worker").getFirst(), success());

        assertThat(jobStatus(jdbc, execution, "wait")).isEqualTo("READY");
        Instant availableAt = jdbc.sql("SELECT available_at FROM job_execution WHERE workflow_execution_id = ? AND step_key = 'wait'")
                .param(execution).query(Instant.class).single();
        assertThat(Duration.between(Instant.now(), availableAt)).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10));
        assertThat(queue.claim(1, "test-worker")).isEmpty();
    }

    @Test
    void aJobFinishingAfterCancellationIsCancelledAndReleasesNothing() {
        long workflow = api.createWorkflow(user, projectId, "Cancelled");
        long a = api.addStep(user, workflow, "a", "HTTP", HTTP_CONFIG);
        long b = api.addStep(user, workflow, "b", "HTTP", HTTP_CONFIG);
        api.setDependencies(user, workflow, b, a);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");
        ClaimedJob running = queue.claim(1, "test-worker").getFirst();

        api.post(user, "/api/executions/" + execution + "/cancel", "");
        assertThat(jobStatus(jdbc, execution, "a")).isEqualTo("RUNNING");
        assertThat(jobStatus(jdbc, execution, "b")).isEqualTo("CANCELLED");

        coordinator.record(running, success());

        assertThat(jobStatus(jdbc, execution, "a")).isEqualTo("CANCELLED");
        assertThat(jobStatus(jdbc, execution, "b")).isEqualTo("CANCELLED");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT status FROM job_attempt WHERE job_execution_id = ?").param(running.id())
                .query(String.class).single()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aSecondResultForTheSameAttemptIsIgnored() {
        long workflow = api.createWorkflow(user, projectId, "Twice");
        api.addStep(user, workflow, "only", "HTTP", HTTP_CONFIG);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");
        ClaimedJob only = queue.claim(1, "test-worker").getFirst();

        coordinator.record(only, success());
        coordinator.record(only, new JobResult.Failure(ErrorType.HTTP_5XX, "late"));

        assertThat(jobStatus(jdbc, execution, "only")).isEqualTo("SUCCEEDED");
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
    }

    private static ClaimedJob job(List<ClaimedJob> claimed, String stepKey) {
        return claimed.stream().filter(job -> job.stepKey().equals(stepKey)).findFirst().orElseThrow();
    }

    private static JobResult success() {
        return new JobResult.Success(JsonNodeFactory.instance.objectNode().put("status", 200));
    }
}
