package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.flowforge.EngineTestData;
import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;

@IntegrationTest
class SchedulerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Scheduler scheduler;

    TestApi api;
    String user;
    long projectId;

    @BeforeEach
    void setUp() {
        EngineTestData.cancelLeftoverWork(jdbc);
        jdbc.sql("UPDATE workflow_schedule SET enabled = false, next_run_at = NULL").update();
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Scheduling");
    }

    @Test
    void aDueScheduleCreatesOneScheduledExecutionAndMovesToItsNextRun() {
        long workflow = activeWorkflow("Due");
        long schedule = schedule(workflow, "*/5 * * * *", """
                { "report": "daily" }
                """);
        Instant dueAt = makeDue(schedule);

        assertThat(scheduler.tick()).isEqualTo(1);

        assertThat(jdbc.sql("""
                SELECT trigger_type || ' ' || schedule_id || ' ' || (scheduled_for = ?) || ' ' || dedup_key || ' '
                       || input::text || ' ' || (triggered_by IS NULL)
                FROM workflow_execution WHERE workflow_id = ?
                """).params(java.sql.Timestamp.from(dueAt), workflow).query(String.class).single())
                .isEqualTo("SCHEDULE " + schedule + " true schedule:" + schedule + ":" + dueAt
                        + " {\"report\": \"daily\"} true");
        assertThat(jdbc.sql("SELECT count(*) FROM job_execution j JOIN workflow_execution e ON e.id = j.workflow_execution_id WHERE e.workflow_id = ?")
                .param(workflow).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_run_at = ? AND next_run_at > now() FROM workflow_schedule WHERE id = ?")
                .params(java.sql.Timestamp.from(dueAt), schedule).query(Boolean.class).single()).isTrue();

        assertThat(scheduler.tick()).isZero();
        assertThat(executionCount(workflow)).isEqualTo(1);
    }

    @Test
    void twoConcurrentTicksCreateExactlyOneExecutionPerDueTime() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                long workflow = activeWorkflow("Concurrent " + round);
                long schedule = schedule(workflow, "*/5 * * * *", "{}");
                makeDue(schedule);

                CyclicBarrier together = new CyclicBarrier(2);
                List<Future<Integer>> ticks = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    ticks.add(executor.submit(() -> {
                        together.await();
                        return scheduler.tick();
                    }));
                }
                int fired = 0;
                for (Future<Integer> tick : ticks) {
                    fired += tick.get();
                }

                assertThat(fired).as("round %d", round).isEqualTo(1);
                assertThat(executionCount(workflow)).as("round %d", round).isEqualTo(1);
                EngineTestData.cancelLeftoverWork(jdbc);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aScheduledRunIsSkippedWhileTheWorkflowIsStillRunning() {
        long workflow = activeWorkflow("Busy");
        api.startExecution(user, workflow, "{}");
        long schedule = schedule(workflow, "*/5 * * * *", "{}");
        Instant dueAt = makeDue(schedule);

        assertThat(scheduler.tick()).isEqualTo(1);

        assertThat(executionCount(workflow)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_run_at = ? AND next_run_at > now() FROM workflow_schedule WHERE id = ?")
                .params(java.sql.Timestamp.from(dueAt), schedule).query(Boolean.class).single()).isTrue();
    }

    @Test
    void afterDowntimeMissedRunsAreNotReplayedOneByOne() {
        long workflow = activeWorkflow("Downtime");
        long schedule = schedule(workflow, "*/5 * * * *", "{}");
        jdbc.sql("UPDATE workflow_schedule SET next_run_at = now() - interval '3 days' WHERE id = ?")
                .param(schedule).update();

        assertThat(scheduler.tick()).isEqualTo(1);

        assertThat(executionCount(workflow)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT next_run_at > now() FROM workflow_schedule WHERE id = ?")
                .param(schedule).query(Boolean.class).single()).isTrue();
    }

    @Test
    void schedulesOfWorkflowsThatAreNotActiveDoNotFire() {
        long workflow = activeWorkflow("Paused workflow");
        long schedule = schedule(workflow, "*/5 * * * *", "{}");
        makeDue(schedule);
        api.post(user, "/api/workflows/" + workflow + "/deactivate", "");

        assertThat(scheduler.tick()).isZero();
        assertThat(executionCount(workflow)).isZero();
    }

    @Test
    void aWorkflowThatBecameInvalidIsSkippedAndTheScheduleMovesOn() {
        long workflow = api.createWorkflow(user, projectId, "Became invalid");
        long step = api.addStep(user, workflow, "call", "HTTP", """
                { "method": "GET", "url": "https://example.com" }
                """);
        api.activate(user, workflow);
        api.put(user, "/api/workflows/" + workflow + "/steps/" + step, """
                { "name": "call", "config": { "method": "GET", "url": "https://example.com/{{secret}}" } }
                """);
        long schedule = schedule(workflow, "*/5 * * * *", "{}");
        Instant dueAt = makeDue(schedule);

        assertThat(scheduler.tick()).isEqualTo(1);

        assertThat(executionCount(workflow)).isZero();
        assertThat(jdbc.sql("SELECT last_run_at = ? AND next_run_at > now() FROM workflow_schedule WHERE id = ?")
                .params(java.sql.Timestamp.from(dueAt), schedule).query(Boolean.class).single()).isTrue();
    }

    private long activeWorkflow(String name) {
        long workflow = api.createWorkflow(user, projectId, name);
        api.addStep(user, workflow, "call", "HTTP", """
                { "method": "GET", "url": "https://example.com" }
                """);
        api.activate(user, workflow);
        return workflow;
    }

    private long schedule(long workflow, String cron, String input) {
        var result = api.post(user, "/api/workflows/" + workflow + "/schedules", """
                { "cronExpression": "%s", "timezone": "UTC", "input": %s }
                """.formatted(cron, input));
        assertThat(result).hasStatus(201);
        return TestApi.idOf(result);
    }

    private Instant makeDue(long schedule) {
        return jdbc.sql("""
                UPDATE workflow_schedule SET next_run_at = date_trunc('second', now()) - interval '1 minute'
                WHERE id = ? RETURNING next_run_at
                """).param(schedule).query(Instant.class).single();
    }

    private long executionCount(long workflow) {
        return jdbc.sql("SELECT count(*) FROM workflow_execution WHERE workflow_id = ?")
                .param(workflow).query(Long.class).single();
    }
}
