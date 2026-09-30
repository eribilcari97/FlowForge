package com.flowforge.service.engine;

import static com.flowforge.EngineTestData.jobStatus;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.flowforge.EngineTestData;
import com.flowforge.FlowforgeApplication;
import com.flowforge.IntegrationTest;
import com.flowforge.MailpitContainer;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;
import com.flowforge.repository.JobQueue;

@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiInstanceTest {

    private static final String SECOND_INSTANCE = "instance-b";
    private static final String HOST_AND_PID_WORKER = ".+:\\d+:flowforge-job-\\d+";
    private static final String SECOND_INSTANCE_WORKER = SECOND_INSTANCE + ":flowforge-job-\\d+";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    JobQueue queue;

    @Autowired
    Scheduler scheduler;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    MailpitContainer mailpit;

    WireMockServer server;
    ConfigurableApplicationContext secondInstance;
    TestApi api;
    String user;
    long projectId;

    @BeforeAll
    void startServerAndSecondInstance() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        secondInstance = new SpringApplicationBuilder(FlowforgeApplication.class)
                .profiles("test")
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.mail.host=" + mailpit.getHost(),
                        "--spring.mail.port=" + mailpit.smtpPort(),
                        "--server.port=0",
                        "--flowforge.worker.instance-id=" + SECOND_INSTANCE);
    }

    @AfterAll
    void stopServerAndSecondInstance() {
        secondInstance.close();
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        server.stubFor(get(urlPathMatching("/work/.*"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(20).withBody("{\"ok\": true}")));
        EngineTestData.cancelLeftoverWork(jdbc);
        jdbc.sql("UPDATE workflow_schedule SET enabled = false, next_run_at = NULL").update();
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Multiple instances");
    }

    @AfterEach
    void stopWorkers() {
        worker.stop();
        secondInstance.getBean(JobWorker.class).stop();
    }

    @Test
    void twoInstancesShareThirtyExecutionsAndRunEveryJobExactlyOnce() {
        long workflow = api.createWorkflow(user, projectId, "Shared work");
        long first = addGetStep(workflow, "first", 3);
        long second = addGetStep(workflow, "second", 3);
        long third = addGetStep(workflow, "third", 3);
        api.setDependencies(user, workflow, third, first, second);
        api.activate(user, workflow);
        for (int i = 0; i < 30; i++) {
            api.startExecution(user, workflow, "{}");
        }

        worker.start();
        secondInstance.getBean(JobWorker.class).start();

        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.sql("""
                SELECT count(*) FROM workflow_execution WHERE workflow_id = ? AND status = 'SUCCEEDED'
                """).param(workflow).query(Long.class).single() == 30);

        assertThat(jdbc.sql("""
                SELECT count(*) FILTER (WHERE j.status = 'SUCCEEDED' AND j.attempt_count = 1)
                FROM job_execution j JOIN workflow_execution e ON e.id = j.workflow_execution_id
                WHERE e.workflow_id = ?
                """).param(workflow).query(Long.class).single()).isEqualTo(90);
        List<String> workerIds = jdbc.sql("""
                SELECT a.worker_id FROM job_attempt a
                JOIN job_execution j ON j.id = a.job_execution_id
                JOIN workflow_execution e ON e.id = j.workflow_execution_id
                WHERE e.workflow_id = ? AND a.status = 'SUCCEEDED'
                """).param(workflow).query(String.class).list();
        assertThat(workerIds).hasSize(90)
                .allMatch(id -> id.matches(HOST_AND_PID_WORKER) || id.matches(SECOND_INSTANCE_WORKER))
                .anyMatch(id -> id.matches(HOST_AND_PID_WORKER))
                .anyMatch(id -> id.matches(SECOND_INSTANCE_WORKER));

        List<ServeEvent> calls = server.getAllServeEvents();
        assertThat(calls).hasSize(90);
        assertThat(calls.stream().map(call -> call.getRequest().getHeader("Idempotency-Key")).distinct())
                .hasSize(90);
    }

    @Test
    void schedulersOfBothInstancesFireEachDueScheduleOnce() throws Exception {
        List<Long> workflows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long workflow = api.createWorkflow(user, projectId, "Scheduled " + i);
            addGetStep(workflow, "call", 3);
            api.activate(user, workflow);
            var schedule = api.post(user, "/api/workflows/" + workflow + "/schedules", """
                    { "cronExpression": "*/5 * * * *", "timezone": "UTC" }
                    """);
            assertThat(schedule).hasStatus(201);
            workflows.add(workflow);
        }
        jdbc.sql("""
                UPDATE workflow_schedule SET next_run_at = date_trunc('second', now()) - interval '1 minute'
                WHERE workflow_id IN (SELECT id FROM workflow WHERE project_id = ?)
                """).param(projectId).update();

        Scheduler secondScheduler = secondInstance.getBean(Scheduler.class);
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstTick = executor.submit(() -> {
                together.await();
                return scheduler.tick();
            });
            Future<Integer> secondTick = executor.submit(() -> {
                together.await();
                return secondScheduler.tick();
            });

            assertThat(firstTick.get() + secondTick.get()).isEqualTo(20);
        } finally {
            executor.shutdownNow();
        }

        for (long workflow : workflows) {
            assertThat(jdbc.sql("SELECT count(*) FROM workflow_execution WHERE workflow_id = ?")
                    .param(workflow).query(Long.class).single()).as("workflow %d", workflow).isEqualTo(1);
        }
    }

    @Test
    void aJobLeftBehindByAStoppedInstanceIsFinishedByTheOtherInstance() {
        long workflow = api.createWorkflow(user, projectId, "Recovered elsewhere");
        addGetStep(workflow, "call", 3);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");
        String stoppedWorker = "stopped-instance:4242:flowforge-job-1";
        queue.claim(1, stoppedWorker, Duration.ZERO);
        jdbc.sql("""
                UPDATE job_execution SET lease_expires_at = now() - interval '1 second'
                WHERE workflow_execution_id = ? AND status = 'RUNNING'
                """).param(execution).update();

        assertThat(secondInstance.getBean(RecoveryTask.class).recoverExpiredLeases()).isEqualTo(1);
        secondInstance.getBean(JobWorker.class).start();

        await().atMost(Duration.ofSeconds(20))
                .until(() -> jobStatus(jdbc, execution, "call").equals("SUCCEEDED"));
        assertThat(jdbc.sql("""
                SELECT a.attempt_number || ' ' || a.status || ' ' || a.worker_id FROM job_attempt a
                JOIN job_execution j ON j.id = a.job_execution_id
                WHERE j.workflow_execution_id = ? ORDER BY a.attempt_number
                """).param(execution).query(String.class).list())
                .satisfiesExactly(
                        attempt -> assertThat(attempt).isEqualTo("1 ABANDONED " + stoppedWorker),
                        attempt -> assertThat(attempt).matches("2 SUCCEEDED " + SECOND_INSTANCE_WORKER));
        server.verify(1, getRequestedFor(urlPathMatching("/work/call")));
    }

    @Test
    void anInstanceWithItsWorkerDisabledOnlyServesTheApi() {
        long workflow = api.createWorkflow(user, projectId, "API only");
        addGetStep(workflow, "call", 3);
        api.activate(user, workflow);
        long execution = api.startExecution(user, workflow, "{}");

        assertThat(secondInstance.getEnvironment().getProperty("flowforge.worker.enabled")).isEqualTo("false");
        assertThat(secondInstance.getBean(JobWorker.class).isRunning()).isFalse();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> jobStatus(jdbc, execution, "call").equals("READY"));
        server.verify(0, getRequestedFor(urlPathMatching("/work/.*")));
    }

    private long addGetStep(long workflow, String key, int maxAttempts) {
        var result = api.post(user, "/api/workflows/" + workflow + "/steps", """
                { "key": "%s", "name": "%s", "jobType": "HTTP",
                  "config": { "method": "GET", "url": "%s/work/%s" },
                  "timeoutSeconds": 10, "maxAttempts": %d, "retryDelaySeconds": 1 }
                """.formatted(key, key, server.baseUrl(), key, maxAttempts));
        assertThat(result).hasStatus(201);
        return TestApi.idOf(result);
    }
}
