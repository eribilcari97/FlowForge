package com.flowforge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;

@IntegrationTest
class DashboardApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    String user;
    long projectId;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Dashboard");
    }

    @Test
    void anEmptyDashboard() {
        assertThat(api.get(user, "/api/dashboard")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "running": { "items": [], "total": 0 },
                          "failedLast24Hours": { "items": [], "total": 0 },
                          "upcomingRuns": []
                        }
                        """);
    }

    @Test
    void showsRunningExecutionsAndOnlyTheFailuresOfTheLast24Hours() {
        long workflow = activeWorkflow("Reports");
        long running = api.startExecution(user, workflow, "{}");
        long recentFailure = api.startExecution(user, workflow, "{}");
        long oldFailure = api.startExecution(user, workflow, "{}");
        fail(recentFailure, "2 hours");
        fail(oldFailure, "25 hours");

        assertThat(api.get(user, "/api/dashboard")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "running": { "items": [ { "id": %d, "workflowName": "Reports", "status": "RUNNING" } ], "total": 1 },
                          "failedLast24Hours": {
                            "items": [ { "id": %d, "status": "FAILED", "errorSummary": "fetch failed: HTTP 500" } ],
                            "total": 1
                          }
                        }
                        """.formatted(running, recentFailure));
    }

    @Test
    void upcomingRunsComeFromEnabledSchedulesOfActiveWorkflowsSoonestFirst() {
        long daily = activeWorkflow("Daily");
        long hourly = activeWorkflow("Hourly");
        long draft = api.createWorkflow(user, projectId, "Draft");
        long dailySchedule = schedule(daily, "0 8 * * *", true);
        long hourlySchedule = schedule(hourly, "0 * * * *", true);
        schedule(hourly, "30 * * * *", false);
        schedule(draft, "* * * * *", true);
        jdbc.sql("UPDATE workflow_schedule SET next_run_at = now() + interval '5 hours' WHERE id = ?")
                .param(dailySchedule).update();
        jdbc.sql("UPDATE workflow_schedule SET next_run_at = now() + interval '10 minutes' WHERE id = ?")
                .param(hourlySchedule).update();

        assertThat(api.get(user, "/api/dashboard")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "upcomingRuns": [
                          { "scheduleId": %d, "workflowId": %d, "workflowName": "Hourly", "cronExpression": "0 * * * *", "timezone": "UTC" },
                          { "scheduleId": %d, "workflowId": %d, "workflowName": "Daily", "cronExpression": "0 8 * * *" }
                        ] }
                        """.formatted(hourlySchedule, hourly, dailySchedule, daily));
        assertThat(api.get(user, "/api/dashboard"))
                .bodyJson().extractingPath("$.upcomingRuns").asArray().hasSize(2);
    }

    @Test
    void otherUsersWorkIsNotShown() {
        long workflow = activeWorkflow("Private");
        api.startExecution(user, workflow, "{}");
        schedule(workflow, "0 8 * * *", true);
        String otherUser = new TestAccounts(mvc).newUserBearer();

        assertThat(api.get(otherUser, "/api/dashboard")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "running": { "total": 0 }, "failedLast24Hours": { "total": 0 }, "upcomingRuns": [] }
                        """);
    }

    @Test
    void requiresAToken() {
        assertThat(mvc.get().uri("/api/dashboard")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private long activeWorkflow(String name) {
        long workflow = api.createWorkflow(user, projectId, name);
        api.addStep(user, workflow, "fetch", "HTTP", """
                { "method": "GET", "url": "https://example.com" }
                """);
        api.activate(user, workflow);
        return workflow;
    }

    private long schedule(long workflow, String cron, boolean enabled) {
        var result = api.post(user, "/api/workflows/" + workflow + "/schedules", """
                { "cronExpression": "%s", "timezone": "UTC", "enabled": %b }
                """.formatted(cron, enabled));
        assertThat(result).hasStatus(201);
        return TestApi.idOf(result);
    }

    private void fail(long execution, String ago) {
        jdbc.sql("""
                UPDATE workflow_execution
                SET status = 'FAILED', finished_at = now() - CAST(? AS interval), error_summary = 'fetch failed: HTTP 500'
                WHERE id = ?
                """).params(ago, execution).update();
    }
}
