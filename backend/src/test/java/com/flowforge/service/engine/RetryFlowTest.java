package com.flowforge.service.engine;

import static com.flowforge.EngineTestData.executionStatus;
import static com.flowforge.EngineTestData.jobStatus;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
class RetryFlowTest {

    private static WireMockServer server;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    TestApi api;
    String user;
    long projectId;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        EngineTestData.cancelLeftoverWork(jdbc);
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Retries");
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void failFailSucceedIsRetriedWithGrowingDelays() {
        server.stubFor(get("/flaky").inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        server.stubFor(get("/flaky").inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("third"));
        server.stubFor(get("/flaky").inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        long workflow = api.createWorkflow(user, projectId, "Flaky");
        addStep(workflow, "flaky", """
                { "method": "GET", "url": "%s/flaky" }
                """, 5, 3, 1);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
        assertThat(attempts(execution, "flaky")).containsExactly(
                "1 FAILED HTTP_5XX true", "2 FAILED HTTP_5XX true", "3 SUCCEEDED null null");
        List<Instant> requestTimes = server.findAll(getRequestedFor(urlPathEqualTo("/flaky"))).stream()
                .map(request -> request.getLoggedDate().toInstant())
                .sorted()
                .toList();
        assertThat(Duration.between(requestTimes.get(0), requestTimes.get(1))).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(Duration.between(requestTimes.get(1), requestTimes.get(2))).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void failingOnEveryAttemptEndsInFailedAndSkipsDownstream() {
        server.stubFor(get("/down").willReturn(aResponse().withStatus(503)));
        server.stubFor(get("/after").willReturn(aResponse().withStatus(200)));
        long workflow = api.createWorkflow(user, projectId, "Always down");
        long down = addStep(workflow, "down", """
                { "method": "GET", "url": "%s/down" }
                """, 5, 3, 1);
        long after = addStep(workflow, "after", """
                { "method": "GET", "url": "%s/after" }
                """, 5, 3, 1);
        api.setDependencies(user, workflow, after, down);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("FAILED");
        assertThat(jobStatus(jdbc, execution, "down")).isEqualTo("FAILED");
        assertThat(jobStatus(jdbc, execution, "after")).isEqualTo("SKIPPED");
        assertThat(attempts(execution, "down")).hasSize(3);
        server.verify(3, getRequestedFor(urlPathEqualTo("/down")));
        assertThat(api.get(user, "/api/executions/" + execution))
                .bodyJson().extractingPath("$.errorSummary")
                .isEqualTo("down failed after 3 attempts: HTTP 503 Service Unavailable");
    }

    @Test
    void aClientErrorIsNotRetried() {
        server.stubFor(get("/bad").willReturn(aResponse().withStatus(400)));
        long workflow = api.createWorkflow(user, projectId, "Bad request");
        addStep(workflow, "bad", """
                { "method": "GET", "url": "%s/bad" }
                """, 5, 3, 1);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        assertThat(attempts(execution, "bad")).containsExactly("1 FAILED HTTP_4XX false");
        server.verify(1, getRequestedFor(urlPathEqualTo("/bad")));
    }

    @Test
    void aTimedOutGetIsRetriedButATimedOutPostFailsWithAnUnknownOutcome() {
        server.stubFor(get("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
        server.stubFor(post("/slow").willReturn(aResponse().withStatus(201).withFixedDelay(3000)));
        long getWorkflow = api.createWorkflow(user, projectId, "Slow GET");
        addStep(getWorkflow, "slow_get", """
                { "method": "GET", "url": "%s/slow" }
                """, 1, 2, 1);
        api.activate(user, getWorkflow);
        long postWorkflow = api.createWorkflow(user, projectId, "Slow POST");
        addStep(postWorkflow, "slow_post", """
                { "method": "POST", "url": "%s/slow" }
                """, 1, 3, 1);
        api.activate(user, postWorkflow);

        long getExecution = api.startExecution(user, getWorkflow, "{}");
        long postExecution = api.startExecution(user, postWorkflow, "{}");

        awaitFinished(getExecution);
        awaitFinished(postExecution);
        assertThat(attempts(getExecution, "slow_get")).containsExactly(
                "1 FAILED TIMEOUT true", "2 FAILED TIMEOUT true");
        assertThat(attempts(postExecution, "slow_post")).containsExactly("1 FAILED TIMEOUT false");
        server.verify(1, postRequestedFor(urlPathEqualTo("/slow")));
        assertThat(jdbc.sql("SELECT last_error FROM job_execution WHERE workflow_execution_id = ?")
                .param(postExecution).query(String.class).single())
                .isEqualTo("No response within 1 s Outcome unknown: check the target system before running again.");
    }

    @Test
    void theRetryCountdownIsVisibleWhileAJobWaitsForItsNextAttempt() {
        server.stubFor(get("/down").willReturn(aResponse().withStatus(503)));
        long workflow = api.createWorkflow(user, projectId, "Waiting to retry");
        addStep(workflow, "down", """
                { "method": "GET", "url": "%s/down" }
                """, 5, 3, 60);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        await().atMost(Duration.ofSeconds(10)).until(() -> attempts(execution, "down").size() == 1
                && jobStatus(jdbc, execution, "down").equals("READY"));
        assertThat(api.get(user, "/api/executions/" + execution))
                .bodyJson().isLenientlyEqualTo("""
                        { "status": "RUNNING", "jobs": [
                          { "stepKey": "down", "status": "READY", "attemptCount": 1, "maxAttempts": 3,
                            "lastError": "HTTP 503 Service Unavailable" }
                        ] }
                        """);
        Instant availableAt = jdbc.sql("SELECT available_at FROM job_execution WHERE workflow_execution_id = ?")
                .param(execution).query(Instant.class).single();
        assertThat(Duration.between(Instant.now(), availableAt)).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(60));
    }

    private long addStep(long workflow, String key, String configTemplate, int timeoutSeconds, int maxAttempts,
            int retryDelaySeconds) {
        String config = configTemplate.formatted(server.baseUrl());
        var result = api.post(user, "/api/workflows/" + workflow + "/steps", """
                { "key": "%s", "name": "%s", "jobType": "HTTP", "config": %s,
                  "timeoutSeconds": %d, "maxAttempts": %d, "retryDelaySeconds": %d }
                """.formatted(key, key, config, timeoutSeconds, maxAttempts, retryDelaySeconds));
        assertThat(result).hasStatus(201);
        return TestApi.idOf(result);
    }

    private List<String> attempts(long execution, String stepKey) {
        return jdbc.sql("""
                SELECT a.attempt_number || ' ' || a.status || ' ' || coalesce(a.error_type, 'null') || ' '
                       || coalesce(a.retryable::text, 'null')
                FROM job_attempt a JOIN job_execution j ON j.id = a.job_execution_id
                WHERE j.workflow_execution_id = ? AND j.step_key = ?
                ORDER BY a.attempt_number
                """).params(execution, stepKey).query(String.class).list();
    }

    private void awaitFinished(long execution) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !executionStatus(jdbc, execution).equals("RUNNING"));
    }
}
