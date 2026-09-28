package com.flowforge.controller;

import static com.flowforge.TestApi.idOf;
import static org.assertj.core.api.Assertions.assertThat;

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
class ActivationApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    String user;
    long workflowId;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        long projectId = api.createProject(user, "Acme Shop Ops");
        workflowId = api.createWorkflow(user, projectId, "Customer onboarding");
    }

    @Test
    void aWorkflowWithoutStepsCannotBeActivated() {
        assertThat(activate())
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "status": 422,
                          "code": "WORKFLOW_INVALID",
                          "detail": "Workflow cannot be activated",
                          "problems": [ { "stepKey": null, "message": "The workflow has no steps" } ]
                        }
                        """);
        assertThat(status()).isEqualTo("DRAFT");
    }

    @Test
    void activationListsEveryProblem() {
        long fetch = idOf(api.addHttpStep(user, workflowId, "fetch_orders"));
        addStep("send_report", """
                { "method": "POST", "url": "https://report.example.com",
                  "body": { "orders": "{{steps.fetch_orders.output.body}}", "token": "{{secret}}" } }
                """);

        assertThat(activate())
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("""
                        { "problems": [
                          { "stepKey": "send_report", "message": "References steps.fetch_orders, which is not upstream of send_report" },
                          { "stepKey": "send_report", "message": "Unknown placeholder {{secret}}" }
                        ] }
                        """);
        assertThat(status()).isEqualTo("DRAFT");
        assertThat(fetch).isPositive();
    }

    @Test
    void aValidWorkflowIsActivatedAndCanReturnToDraft() {
        long fetch = idOf(api.addHttpStep(user, workflowId, "fetch_orders"));
        long report = addStep("send_report", """
                { "method": "POST", "url": "https://report.example.com",
                  "body": { "orders": "{{steps.fetch_orders.output.body}}", "run": "{{execution.runNumber}}" } }
                """);
        assertThat(api.setDependencies(user, workflowId, report, fetch)).hasStatusOk();

        assertThat(activate()).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "status": "ACTIVE", "version": 1 }
                        """);

        assertThat(api.post(user, "/api/workflows/" + workflowId + "/deactivate", "")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("DRAFT");
    }

    @Test
    void archivedWorkflowsCannotBeActivatedOrDeactivated() {
        api.addHttpStep(user, workflowId, "fetch_orders");
        jdbc.sql("update workflow set status = 'ARCHIVED' where id = ?").param(workflowId).update();

        assertThat(activate()).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATE");
        assertThat(api.post(user, "/api/workflows/" + workflowId + "/deactivate", ""))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void anotherUsersWorkflowCannotBeActivated() {
        api.addHttpStep(user, workflowId, "fetch_orders");
        String otherUser = new TestAccounts(mvc).newUserBearer();

        assertThat(api.post(otherUser, "/api/workflows/" + workflowId + "/activate", ""))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(status()).isEqualTo("DRAFT");
    }

    private MvcTestResult activate() {
        return api.post(user, "/api/workflows/" + workflowId + "/activate", "");
    }

    private long addStep(String key, String config) {
        MvcTestResult result = api.post(user, "/api/workflows/" + workflowId + "/steps", """
                { "key": "%s", "name": "%s", "jobType": "HTTP", "config": %s }
                """.formatted(key, key, config));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return idOf(result);
    }

    private String status() {
        return jdbc.sql("select status from workflow where id = ?").param(workflowId).query(String.class).single();
    }
}
