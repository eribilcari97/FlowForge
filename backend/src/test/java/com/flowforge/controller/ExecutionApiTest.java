package com.flowforge.controller;

import static com.flowforge.TestAccounts.body;
import static com.flowforge.TestApi.idOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;

@IntegrationTest
class ExecutionApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    String user;
    long projectId;
    long workflowId;
    long fetchStep;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Acme Shop Ops");
        workflowId = activeDiamond("Diamond");
    }

    private long activeDiamond(String name) {
        long workflow = api.createWorkflow(user, projectId, name);
        long a = idOf(api.addHttpStep(user, workflow, "a"));
        long b = idOf(api.addHttpStep(user, workflow, "b"));
        long c = idOf(api.addHttpStep(user, workflow, "c"));
        long d = idOf(api.addHttpStep(user, workflow, "d"));
        api.setDependencies(user, workflow, b, a);
        api.setDependencies(user, workflow, c, a);
        api.setDependencies(user, workflow, d, b, c);
        fetchStep = a;
        assertThat(api.post(user, "/api/workflows/" + workflow + "/activate", "")).hasStatusOk();
        return workflow;
    }

    @Test
    void startingADiamondCreatesJobsInTheirInitialStates() {
        MvcTestResult started = start(workflowId, null, """
                { "input": { "customer": { "email": "jane@example.com" } } }
                """);
        long executionId = idOf(started);

        assertThat(started).hasStatus(HttpStatus.ACCEPTED)
                .hasHeader(HttpHeaders.LOCATION, "/api/executions/" + executionId)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "workflowId": %d,
                          "workflowName": "Diamond",
                          "runNumber": 1,
                          "status": "RUNNING",
                          "triggerType": "MANUAL",
                          "input": { "customer": { "email": "jane@example.com" } },
                          "finishedAt": null,
                          "jobs": [
                            { "stepKey": "a", "jobType": "HTTP", "status": "READY", "attemptCount": 0, "maxAttempts": 3, "dependsOn": [] },
                            { "stepKey": "b", "status": "PENDING", "availableAt": null, "dependsOn": ["a"] },
                            { "stepKey": "c", "status": "PENDING", "availableAt": null, "dependsOn": ["a"] },
                            { "stepKey": "d", "status": "PENDING", "availableAt": null, "dependsOn": ["b", "c"] }
                          ]
                        }
                        """.formatted(workflowId));
        assertThat(JsonPath.<String>read(body(started), "$.jobs[0].availableAt")).isNotNull();

        String copiedConfig = jdbc.sql("select config::text from job_execution where workflow_execution_id = ? and step_key = 'a'")
                .param(executionId).query(String.class).single();
        assertThat(copiedConfig).contains("https://example.com/a");
    }

    @Test
    void runNumbersIncreasePerWorkflowAndTheWorkflowVersionIsUntouched() {
        assertThat(start(workflowId, null, null)).bodyJson().extractingPath("$.runNumber").isEqualTo(1);
        assertThat(start(workflowId, null, null)).bodyJson().extractingPath("$.runNumber").isEqualTo(2);

        long otherWorkflow = activeDiamond("Other");
        assertThat(start(otherWorkflow, null, null)).bodyJson().extractingPath("$.runNumber").isEqualTo(1);

        assertThat(api.get(user, "/api/workflows/" + workflowId))
                .bodyJson().extractingPath("$.version").isEqualTo(1);
    }

    @Test
    void aRootDelayStepBecomesReadyAfterItsDuration() {
        long workflow = api.createWorkflow(user, projectId, "Delayed");
        api.post(user, "/api/workflows/" + workflow + "/steps", """
                { "key": "wait", "name": "Wait", "jobType": "DELAY", "config": { "duration": "PT1H" } }
                """);
        api.post(user, "/api/workflows/" + workflow + "/activate", "");

        MvcTestResult started = start(workflow, null, null);

        Instant createdAt = Instant.parse(JsonPath.read(body(started), "$.createdAt"));
        Instant availableAt = Instant.parse(JsonPath.read(body(started), "$.jobs[0].availableAt"));
        assertThat(Duration.between(createdAt, availableAt)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void theSameIdempotencyKeyReturnsTheExistingExecution() {
        MvcTestResult first = start(workflowId, "click-1", null);
        MvcTestResult second = start(workflowId, "click-1", null);
        MvcTestResult other = start(workflowId, "click-2", null);

        assertThat(first).hasStatus(HttpStatus.ACCEPTED);
        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(idOf(other)).isNotEqualTo(idOf(first));
        assertThat(executionCount(workflowId)).isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyCreateOneExecution() throws Exception {
        for (int round = 0; round < 5; round++) {
            String key = "double-click-" + round;
            List<Callable<MvcTestResult>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(() -> start(workflowId, key, null));
            }

            List<MvcTestResult> results = runConcurrently(requests);

            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsOnlyOnce(202)
                    .containsOnly(200, 202);
            assertThat(results).extracting(TestApi::idOf).containsOnly(idOf(results.getFirst()));
        }
        assertThat(executionCount(workflowId)).isEqualTo(5);
    }

    @Test
    void invalidIdempotencyKeyOrInputIsRejected() {
        assertThat(start(workflowId, "x".repeat(101), null))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("Idempotency-Key");
        assertThat(start(workflowId, null, """
                { "input": [1, 2] }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("input");
    }

    @Test
    void onlyActiveWorkflowsCanBeRun() {
        long draft = api.createWorkflow(user, projectId, "Draft");
        api.addHttpStep(user, draft, "only");

        assertThat(start(draft, null, null))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATE");
    }

    @Test
    void aWorkflowThatBecameInvalidWhileActiveCannotBeRun() {
        api.put(user, "/api/workflows/" + workflowId + "/steps/" + fetchStep, """
                { "name": "a", "config": { "method": "GET", "url": "https://example.com/{{secret}}" } }
                """);

        assertThat(start(workflowId, null, null))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("WORKFLOW_INVALID");
        assertThat(executionCount(workflowId)).isZero();
    }

    @Test
    void editingTheWorkflowAfterStartingDoesNotChangeTheExecution() {
        long executionId = idOf(start(workflowId, null, null));

        api.put(user, "/api/workflows/" + workflowId + "/steps/" + fetchStep, """
                { "name": "Renamed", "config": { "method": "POST", "url": "https://changed.example.com" }, "maxAttempts": 9 }
                """);
        long e = idOf(api.addHttpStep(user, workflowId, "e"));
        assertThat(e).isPositive();

        assertThat(api.get(user, "/api/executions/" + executionId)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "jobs": [
                          { "stepKey": "a", "maxAttempts": 3 },
                          { "stepKey": "b" },
                          { "stepKey": "c" },
                          { "stepKey": "d" }
                        ] }
                        """);
        assertThat(api.get(user, "/api/executions/" + executionId))
                .bodyJson().extractingPath("$.jobs").asArray().hasSize(4);
        String copiedConfig = jdbc.sql("select config::text from job_execution where workflow_execution_id = ? and step_key = 'a'")
                .param(executionId).query(String.class).single();
        assertThat(copiedConfig).contains("https://example.com/a").doesNotContain("changed");
    }

    @Test
    void cancellingSetsTheExecutionAndItsWaitingJobsToCancelled() {
        long executionId = idOf(start(workflowId, null, null));

        MvcTestResult cancelled = api.post(user, "/api/executions/" + executionId + "/cancel", "");

        assertThat(cancelled).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "status": "CANCELLED", "jobs": [
                          { "stepKey": "a", "status": "CANCELLED" },
                          { "stepKey": "b", "status": "CANCELLED" },
                          { "stepKey": "c", "status": "CANCELLED" },
                          { "stepKey": "d", "status": "CANCELLED" }
                        ] }
                        """);
        Instant finishedAt = Instant.parse(JsonPath.read(body(cancelled), "$.finishedAt"));
        assertThat(finishedAt).isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));

        assertThat(api.post(user, "/api/executions/" + executionId + "/cancel", ""))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATE");
    }

    @Test
    void executionListsAreNewestFirstPagedAndFilterable() {
        long first = idOf(start(workflowId, null, null));
        long second = idOf(start(workflowId, null, null));
        long third = idOf(start(workflowId, null, null));
        api.post(user, "/api/executions/" + second + "/cancel", "");

        assertThat(api.get(user, "/api/workflows/" + workflowId + "/executions?page=0&size=2")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "items": [ { "id": %d, "runNumber": 3 }, { "id": %d, "runNumber": 2 } ], "page": 0, "size": 2, "total": 3 }
                        """.formatted(third, second));

        assertThat(api.get(user, "/api/executions?status=CANCELLED")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "items": [ { "id": %d, "workflowName": "Diamond", "status": "CANCELLED" } ], "total": 1 }
                        """.formatted(second));
        assertThat(api.get(user, "/api/executions?status=RUNNING"))
                .bodyJson().extractingPath("$.items[*].id").asArray()
                .containsExactly((int) third, (int) first);
    }

    @Test
    void anotherUsersExecutionsAreNotFound() {
        long executionId = idOf(start(workflowId, null, null));
        String otherUser = new TestAccounts(mvc).newUserBearer();

        assertThat(api.get(otherUser, "/api/executions/" + executionId)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.post(otherUser, "/api/executions/" + executionId + "/cancel", "")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.get(otherUser, "/api/workflows/" + workflowId + "/executions")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/workflows/" + workflowId + "/executions")
                .header(HttpHeaders.AUTHORIZATION, otherUser)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.get(otherUser, "/api/executions")).bodyJson().extractingPath("$.total").isEqualTo(0);

        assertThat(api.get(user, "/api/executions/" + executionId))
                .bodyJson().extractingPath("$.status").isEqualTo("RUNNING");
    }

    @Test
    void deletingAWorkflowWithExecutionsArchivesItInstead() {
        long executionId = idOf(start(workflowId, null, null));

        assertThat(api.delete(user, "/api/workflows/" + workflowId)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(api.get(user, "/api/workflows/" + workflowId)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("ARCHIVED");
        assertThat(api.get(user, "/api/executions/" + executionId)).hasStatusOk();
        assertThat(start(workflowId, null, null)).hasStatus(HttpStatus.CONFLICT);

        long replacement = api.createWorkflow(user, projectId, "Diamond");
        assertThat(replacement).isNotEqualTo(workflowId);
    }

    private MvcTestResult start(long workflow, String idempotencyKey, String json) {
        var request = mvc.post().uri("/api/workflows/" + workflow + "/executions")
                .header(HttpHeaders.AUTHORIZATION, user);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private long executionCount(long workflow) {
        return jdbc.sql("select count(*) from workflow_execution where workflow_id = ?")
                .param(workflow).query(Long.class).single();
    }

    private static List<MvcTestResult> runConcurrently(List<Callable<MvcTestResult>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (Callable<MvcTestResult> action : actions) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
