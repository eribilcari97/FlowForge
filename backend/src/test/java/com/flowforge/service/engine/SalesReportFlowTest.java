package com.flowforge.service.engine;

import static com.flowforge.EngineTestData.executionStatus;
import static com.flowforge.EngineTestData.jobStatus;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

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
import com.flowforge.MailpitContainer;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;

@IntegrationTest
class SalesReportFlowTest {

    private static WireMockServer shop;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    MailpitContainer mailpit;

    TestApi api;
    String user;

    @BeforeAll
    static void startShop() {
        shop = new WireMockServer(wireMockConfig().dynamicPort());
        shop.start();
        shop.stubFor(get("/orders").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        { "orders": [
                          { "id": "o-1001", "total": 25.50, "status": "PAID" },
                          { "id": "o-1002", "total": 99.00, "status": "PAID" },
                          { "id": "o-1003", "total": 12.25, "status": "REFUNDED" }
                        ] }
                        """)));
    }

    @AfterAll
    static void stopShop() {
        shop.stop();
    }

    @BeforeEach
    void setUp() {
        EngineTestData.cancelLeftoverWork(jdbc);
        mailpit.deleteAllMessages();
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void theDailySalesReportRunsEndToEndAndTheEmailArrives() {
        long project = api.createProject(user, "Acme Shop Ops");
        long workflow = api.createWorkflow(user, project, "Daily sales report");
        long fetch = api.addStep(user, workflow, "fetch_orders", "HTTP", """
                { "method": "GET", "url": "%s/orders" }
                """.formatted(shop.baseUrl()));
        long revenue = api.addStep(user, workflow, "calculate_revenue", "TRANSFORM", """
                { "expression": "{ 'revenue': $sum(steps.fetch_orders.output.body.orders[status='PAID'].total), 'paidOrders': $count(steps.fetch_orders.output.body.orders[status='PAID']), 'refunds': $count(steps.fetch_orders.output.body.orders[status='REFUNDED']) }" }
                """);
        long report = api.addStep(user, workflow, "send_report", "EMAIL", """
                {
                  "to": ["{{input.recipient}}"],
                  "subject": "Daily sales: {{steps.calculate_revenue.output.revenue}} EUR",
                  "text": "Revenue: {{steps.calculate_revenue.output.revenue}} EUR from {{steps.calculate_revenue.output.paidOrders}} paid orders ({{steps.calculate_revenue.output.refunds}} refunds). Run {{execution.runNumber}}."
                }
                """);
        api.setDependencies(user, workflow, revenue, fetch);
        api.setDependencies(user, workflow, report, revenue);
        api.activate(user, workflow);

        long execution = api.startExecution(user, workflow, """
                { "recipient": "owner@acme.example" }
                """);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> !executionStatus(jdbc, execution).equals("RUNNING"));
        assertThat(executionStatus(jdbc, execution)).isEqualTo("SUCCEEDED");
        assertThat(jobStatus(jdbc, execution, "calculate_revenue")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT output::text FROM job_execution WHERE workflow_execution_id = ? AND step_key = 'calculate_revenue'")
                .param(execution).query(String.class).single())
                .isEqualTo("{\"refunds\": 1, \"revenue\": 124.5, \"paidOrders\": 2}");
        assertThat(mailpit.messages()).singleElement().satisfies(message -> {
            assertThat(message.to()).containsExactly("owner@acme.example");
            assertThat(message.subject()).isEqualTo("Daily sales: 124.5 EUR");
            assertThat(message.text()).isEqualTo("Revenue: 124.5 EUR from 2 paid orders (1 refunds). Run 1.");
        });
    }
}
