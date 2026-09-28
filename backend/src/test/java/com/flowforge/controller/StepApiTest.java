package com.flowforge.controller;

import static com.flowforge.TestApi.idOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;

@IntegrationTest
class StepApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    String user;
    long projectId;
    long workflowId;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Acme Shop Ops");
        workflowId = api.createWorkflow(user, projectId, "Customer onboarding");
    }

    @Test
    void addsAnHttpStepWithDefaultLimits() {
        MvcTestResult created = api.post(user, stepsUri(), """
                {
                  "key": "create_crm_contact",
                  "name": "Create CRM contact",
                  "jobType": "HTTP",
                  "config": {
                    "method": "POST",
                    "url": "https://crm.example.com/api/contacts",
                    "body": { "email": "{{input.customer.email}}" }
                  }
                }
                """);

        assertThat(created).hasStatus(HttpStatus.CREATED)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "key": "create_crm_contact",
                          "name": "Create CRM contact",
                          "jobType": "HTTP",
                          "config": {
                            "method": "POST",
                            "url": "https://crm.example.com/api/contacts",
                            "body": { "email": "{{input.customer.email}}" }
                          },
                          "timeoutSeconds": 30,
                          "maxAttempts": 3,
                          "retryDelaySeconds": 10
                        }
                        """);

        assertThat(api.get(user, "/api/workflows/" + workflowId))
                .bodyJson().extractingPath("$.steps[0].config.body.email").isEqualTo("{{input.customer.email}}");
    }

    @Test
    void addsADelayStepWithExplicitLimits() {
        assertThat(api.post(user, stepsUri(), """
                {
                  "key": "wait_1_day",
                  "name": "Wait one day",
                  "jobType": "DELAY",
                  "config": { "duration": "P1D" },
                  "timeoutSeconds": 5,
                  "maxAttempts": 1,
                  "retryDelaySeconds": 60
                }
                """))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().isLenientlyEqualTo("""
                        { "jobType": "DELAY", "config": { "duration": "P1D" }, "timeoutSeconds": 5, "maxAttempts": 1, "retryDelaySeconds": 60 }
                        """);
    }

    @Test
    void stepKeysAreUniqueWithinAWorkflow() {
        assertThat(api.addHttpStep(user, workflowId, "fetch_orders")).hasStatus(HttpStatus.CREATED);

        assertThat(api.addHttpStep(user, workflowId, "fetch_orders"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("DUPLICATE_NAME");

        long otherWorkflow = api.createWorkflow(user, projectId, "Other");
        assertThat(api.addHttpStep(user, otherWorkflow, "fetch_orders")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void invalidStepFieldsAreRejected() {
        assertThat(api.post(user, stepsUri(), """
                {
                  "key": "Fetch-Orders",
                  "name": "Fetch orders",
                  "jobType": "HTTP",
                  "config": { "method": "GET", "url": "https://example.com" },
                  "timeoutSeconds": 301,
                  "maxAttempts": 0
                }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("key", "timeoutSeconds", "maxAttempts");
    }

    @Test
    void invalidConfigIsRejectedWithFieldPaths() {
        assertThat(api.post(user, stepsUri(), """
                {
                  "key": "fetch_orders",
                  "name": "Fetch orders",
                  "jobType": "HTTP",
                  "config": { "method": "GET", "url": "not a url" }
                }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "code": "VALIDATION_ERROR",
                          "errors": [ { "field": "config.url", "message": "must be an absolute http or https URL" } ]
                        }
                        """);
    }

    @Test
    void unknownJobTypeIsRejected() {
        assertThat(api.post(user, stepsUri(), """
                { "key": "run_script", "name": "Run script", "jobType": "SCRIPT", "config": {} }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void updatesAStepAndValidatesTheConfigAgainstItsStoredJobType() {
        long stepId = idOf(api.addHttpStep(user, workflowId, "fetch_orders"));

        assertThat(api.put(user, stepUri(stepId), """
                {
                  "name": "Fetch paid orders",
                  "config": { "method": "GET", "url": "https://shop.example.com/orders?status=paid" },
                  "maxAttempts": 5
                }
                """))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "key": "fetch_orders", "name": "Fetch paid orders", "jobType": "HTTP", "maxAttempts": 5 }
                        """);

        assertThat(api.put(user, stepUri(stepId), """
                { "name": "Now a delay?", "config": { "duration": "PT5S" } }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().contains("config.duration");
    }

    @Test
    void keyAndJobTypeCannotBeChanged() {
        long stepId = idOf(api.addHttpStep(user, workflowId, "fetch_orders"));

        assertThat(api.put(user, stepUri(stepId), """
                {
                  "key": "renamed",
                  "jobType": "DELAY",
                  "name": "Fetch orders",
                  "config": { "method": "GET", "url": "https://example.com" }
                }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");

        assertThat(api.get(user, "/api/workflows/" + workflowId))
                .bodyJson().isLenientlyEqualTo("""
                        { "steps": [ { "id": %d, "key": "fetch_orders", "jobType": "HTTP" } ] }
                        """.formatted(stepId));
    }

    @Test
    void deletesAStep() {
        long stepId = idOf(api.addHttpStep(user, workflowId, "fetch_orders"));

        assertThat(api.delete(user, stepUri(stepId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(api.get(user, "/api/workflows/" + workflowId))
                .bodyJson().extractingPath("$.steps").asArray().isEmpty();
        assertThat(api.delete(user, stepUri(stepId))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStepIsOnlyReachableThroughItsOwnWorkflow() {
        long stepId = idOf(api.addHttpStep(user, workflowId, "fetch_orders"));
        long otherWorkflow = api.createWorkflow(user, projectId, "Other");

        assertThat(api.put(user, "/api/workflows/" + otherWorkflow + "/steps/" + stepId, """
                { "name": "Moved", "config": { "method": "GET", "url": "https://example.com" } }
                """))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.detail").isEqualTo("Step not found");
        assertThat(api.delete(user, "/api/workflows/" + otherWorkflow + "/steps/" + stepId))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aWorkflowHasAtMost30Steps() {
        addSteps(workflowId, 30);

        assertThat(api.addHttpStep(user, workflowId, "step_31"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("STEP_LIMIT_REACHED");
    }

    @Test
    void concurrentAddsNeverExceedTheStepLimit() throws Exception {
        for (int round = 0; round < 3; round++) {
            long workflow = api.createWorkflow(user, projectId, "Concurrent " + round);
            addSteps(workflow, 29);

            List<Integer> statuses = addConcurrently(workflow, "last_a", "last_b");

            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
            long stepCount = jdbc.sql("select count(*) from workflow_step where workflow_id = ?")
                    .param(workflow).query(Long.class).single();
            assertThat(stepCount).isEqualTo(30);
        }
    }

    private List<Integer> addConcurrently(long workflow, String... keys) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(keys.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String key : keys) {
                Callable<Integer> add = () -> {
                    start.await();
                    return api.addHttpStep(user, workflow, key).getResponse().getStatus();
                };
                results.add(executor.submit(add));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private void addSteps(long workflow, int count) {
        for (int i = 1; i <= count; i++) {
            assertThat(api.addHttpStep(user, workflow, "step_" + i)).hasStatus(HttpStatus.CREATED);
        }
    }

    private String stepsUri() {
        return "/api/workflows/" + workflowId + "/steps";
    }

    private String stepUri(long stepId) {
        return stepsUri() + "/" + stepId;
    }
}
