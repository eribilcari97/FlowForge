package com.flowforge.service.engine;

import static com.flowforge.EngineTestData.executionStatus;
import static com.flowforge.EngineTestData.jobStatus;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
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
class EngineFlowTest {

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
        projectId = api.createProject(user, "Engine flows");
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void aLinearWorkflowRunsItsStepsInOrderAndSucceeds() {
        server.stubFor(get(urlPathEqualTo("/step/a")).willReturn(aResponse().withStatus(200).withBody("a")));
        server.stubFor(get(urlPathEqualTo("/step/b")).willReturn(aResponse().withStatus(200).withBody("b")));
        server.stubFor(get(urlPathEqualTo("/step/c")).willReturn(aResponse().withStatus(200).withBody("c")));
        long workflow = api.createWorkflow(user, projectId, "Linear");
        long a = api.addStep(user, workflow, "a", "HTTP", getConfig("/step/a"));
        long b = api.addStep(user, workflow, "b", "HTTP", getConfig("/step/b"));
        long c = api.addStep(user, workflow, "c", "HTTP", getConfig("/step/c"));
        api.setDependencies(user, workflow, b, a);
        api.setDependencies(user, workflow, c, b);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
        assertThat(requestedPathsInOrder()).containsExactly("/step/a", "/step/b", "/step/c");
    }

    @Test
    void aDiamondPassesDataBetweenStepsAndJoinsBeforeTheLastStep() {
        server.stubFor(post("/contacts").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        { "id": 11 }
                        """)));
        server.stubFor(get(urlPathEqualTo("/accounts/11")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        { "accountNumber": "ACME-104" }
                        """)));
        server.stubFor(get(urlPathEqualTo("/plans")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        { "plan": "PRO", "seats": 5 }
                        """)));
        server.stubFor(post("/notify").willReturn(aResponse().withStatus(204)));

        long workflow = api.createWorkflow(user, projectId, "Diamond");
        long contact = api.addStep(user, workflow, "create_contact", "HTTP", """
                { "method": "POST", "url": "%s/contacts", "body": { "email": "{{input.customer.email}}" }, "expectedStatus": [201] }
                """.formatted(server.baseUrl()));
        long account = api.addStep(user, workflow, "fetch_account", "HTTP", getConfig(
                "/accounts/{{steps.create_contact.output.body.id}}"));
        long plan = api.addStep(user, workflow, "fetch_plan", "HTTP", getConfig(
                "/plans?email={{input.customer.email}}"));
        long notify = api.addStep(user, workflow, "notify", "HTTP", """
                { "method": "POST", "url": "%s/notify",
                  "body": { "contactId": "{{steps.create_contact.output.body.id}}",
                            "account": "{{steps.fetch_account.output.body.accountNumber}}",
                            "plan": "{{steps.fetch_plan.output.body}}",
                            "text": "Run {{execution.runNumber}} for {{input.customer.email}}" } }
                """.formatted(server.baseUrl()));
        api.setDependencies(user, workflow, account, contact);
        api.setDependencies(user, workflow, plan, contact);
        api.setDependencies(user, workflow, notify, account, plan);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, """
                { "customer": { "email": "jane@example.com" } }
                """);

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
        server.verify(postRequestedFor(urlEqualTo("/contacts")).withRequestBody(equalToJson("""
                { "email": "jane@example.com" }
                """)));
        server.verify(getRequestedFor(urlEqualTo("/plans?email=jane@example.com")));
        server.verify(postRequestedFor(urlEqualTo("/notify")).withRequestBody(equalToJson("""
                { "contactId": 11, "account": "ACME-104", "plan": { "plan": "PRO", "seats": 5 },
                  "text": "Run 1 for jane@example.com" }
                """)));
        List<String> order = requestedPathsInOrder();
        assertThat(order.getFirst()).isEqualTo("/contacts");
        assertThat(order.getLast()).isEqualTo("/notify");
    }

    @Test
    void aFailingStepSkipsItsDownstreamStepsWhileAnIndependentBranchCompletes() {
        server.stubFor(get(urlPathEqualTo("/broken")).willReturn(aResponse().withStatus(500)));
        server.stubFor(get(urlPathEqualTo("/fine")).willReturn(aResponse().withStatus(200)));
        long workflow = api.createWorkflow(user, projectId, "Failure");
        long broken = api.addStep(user, workflow, "broken", "HTTP", getConfig("/broken"));
        long after = api.addStep(user, workflow, "after_broken", "HTTP", getConfig("/fine"));
        api.addStep(user, workflow, "independent", "HTTP", getConfig("/fine"));
        api.setDependencies(user, workflow, after, broken);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("FAILED");
        assertThat(jobStatus(jdbc, execution, "broken")).isEqualTo("FAILED");
        assertThat(jobStatus(jdbc, execution, "after_broken")).isEqualTo("SKIPPED");
        assertThat(jobStatus(jdbc, execution, "independent")).isEqualTo("SUCCEEDED");
        assertThat(api.get(user, "/api/executions/" + execution))
                .bodyJson().extractingPath("$.errorSummary").isEqualTo("broken failed: HTTP 500 Internal Server Error");
    }

    @Test
    void aMissingPlaceholderValueFailsTheJobAndTheJobDetailShowsWhy() {
        server.stubFor(get(urlPathEqualTo("/customers")).willReturn(aResponse().withStatus(200)));
        long workflow = api.createWorkflow(user, projectId, "Missing value");
        api.addStep(user, workflow, "lookup", "HTTP", getConfig("/customers?phone={{input.customer.phone}}"));
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, """
                { "customer": { "email": "jane@example.com" } }
                """);

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("FAILED");
        assertThat(server.getAllServeEvents()).isEmpty();
        long jobId = jdbc.sql("SELECT id FROM job_execution WHERE workflow_execution_id = ?")
                .param(execution).query(Long.class).single();
        assertThat(api.get(user, "/api/job-executions/" + jobId)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "stepKey": "lookup",
                          "status": "FAILED",
                          "attemptCount": 1,
                          "lastError": "No value for {{input.customer.phone}}",
                          "output": null,
                          "attempts": [
                            { "number": 1, "status": "FAILED", "errorType": "PLACEHOLDER_MISSING",
                              "errorMessage": "No value for {{input.customer.phone}}" }
                          ]
                        }
                        """);
    }

    @Test
    void aDelayStepHoldsBackTheStepsAfterIt() {
        server.stubFor(get(urlPathEqualTo("/before")).willReturn(aResponse().withStatus(200)));
        server.stubFor(get(urlPathEqualTo("/after")).willReturn(aResponse().withStatus(200)));
        long workflow = api.createWorkflow(user, projectId, "Delayed");
        long before = api.addStep(user, workflow, "before", "HTTP", getConfig("/before"));
        long wait = api.addStep(user, workflow, "wait", "DELAY", """
                { "duration": "PT2S" }
                """);
        long after = api.addStep(user, workflow, "after", "HTTP", getConfig("/after"));
        api.setDependencies(user, workflow, wait, before);
        api.setDependencies(user, workflow, after, wait);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
        List<ServeEvent> events = eventsInOrder();
        Duration gap = Duration.between(events.get(0).getRequest().getLoggedDate().toInstant(),
                events.get(1).getRequest().getLoggedDate().toInstant());
        assertThat(gap).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theJobDetailShowsTheConfigUsedTheOutputAndTheAttempt() {
        server.stubFor(get(urlPathEqualTo("/orders")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        { "count": 3 }
                        """)));
        long workflow = api.createWorkflow(user, projectId, "Detail");
        api.addStep(user, workflow, "fetch_orders", "HTTP", getConfig("/orders"));
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, "{}");

        awaitFinished(execution);
        long jobId = jdbc.sql("SELECT id FROM job_execution WHERE workflow_execution_id = ?")
                .param(execution).query(Long.class).single();
        assertThat(api.get(user, "/api/job-executions/" + jobId)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "executionId": %d,
                          "stepKey": "fetch_orders",
                          "status": "SUCCEEDED",
                          "config": { "method": "GET", "url": "%s/orders" },
                          "output": { "status": 200, "body": { "count": 3 } },
                          "lastError": null,
                          "attempts": [ { "number": 1, "status": "SUCCEEDED", "errorType": null } ]
                        }
                        """.formatted(execution, server.baseUrl()));
        String otherUser = new TestAccounts(mvc).newUserBearer();
        assertThat(api.get(otherUser, "/api/job-executions/" + jobId)).hasStatus(404);
    }

    private String getConfig(String path) {
        return """
                { "method": "GET", "url": "%s%s" }
                """.formatted(server.baseUrl(), path);
    }

    private void awaitFinished(long execution) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !executionStatus(jdbc, execution).equals("RUNNING"));
    }

    private List<ServeEvent> eventsInOrder() {
        return server.getAllServeEvents().stream()
                .sorted(Comparator.comparing(event -> event.getRequest().getLoggedDate()))
                .toList();
    }

    private List<String> requestedPathsInOrder() {
        return eventsInOrder().stream()
                .map(event -> event.getRequest().getUrl().split("\\?")[0])
                .toList();
    }
}
