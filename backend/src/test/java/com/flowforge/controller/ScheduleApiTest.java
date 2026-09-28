package com.flowforge.controller;

import static com.flowforge.TestAccounts.body;
import static com.flowforge.TestApi.idOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
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
class ScheduleApiTest {

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
        long projectId = api.createProject(user, "Schedules");
        workflowId = api.createWorkflow(user, projectId, "Daily report");
    }

    @Test
    void createsAScheduleWithItsNextRunTime() {
        MvcTestResult created = create("""
                { "cronExpression": "*/5 * * * *", "timezone": "Europe/Berlin", "input": { "report": "daily" } }
                """);

        assertThat(created).hasStatus(HttpStatus.CREATED)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "workflowId": %d,
                          "cronExpression": "*/5 * * * *",
                          "timezone": "Europe/Berlin",
                          "input": { "report": "daily" },
                          "enabled": true,
                          "lastRunAt": null
                        }
                        """.formatted(workflowId));
        Instant nextRunAt = Instant.parse(JsonPath.read(body(created), "$.nextRunAt"));
        assertThat(Duration.between(Instant.now(), nextRunAt))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(5));

        assertThat(api.get(user, schedulesUri())).hasStatusOk()
                .bodyJson().extractingPath("$[*].cronExpression").asArray().containsExactly("*/5 * * * *");
    }

    @Test
    void pausingClearsTheNextRunAndResumingCalculatesItAgain() {
        long id = idOf(create("""
                { "cronExpression": "0 8 * * *", "timezone": "UTC" }
                """));

        assertThat(api.put(user, schedulesUri() + "/" + id, """
                { "cronExpression": "0 8 * * *", "timezone": "UTC", "enabled": false }
                """))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "enabled": false, "nextRunAt": null }
                        """);

        MvcTestResult resumed = api.put(user, schedulesUri() + "/" + id, """
                { "cronExpression": "30 6 * * 1", "timezone": "Europe/Berlin", "enabled": true }
                """);
        assertThat(resumed).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "cronExpression": "30 6 * * 1", "timezone": "Europe/Berlin", "enabled": true, "input": {} }
                        """);
        assertThat(JsonPath.<String>read(body(resumed), "$.nextRunAt")).isNotNull();
    }

    @Test
    void invalidCronTimeZoneOrInputIsRejected() {
        assertThat(create("""
                { "cronExpression": "0 0 8 * * *", "timezone": "Berlin", "input": [1] }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("cronExpression", "timezone", "input");
    }

    @Test
    void deletesASchedule() {
        long id = idOf(create("""
                { "cronExpression": "0 8 * * *", "timezone": "UTC" }
                """));

        assertThat(api.delete(user, schedulesUri() + "/" + id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(api.get(user, schedulesUri())).bodyJson().extractingPath("$").asArray().isEmpty();
        assertThat(api.delete(user, schedulesUri() + "/" + id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void schedulesOfArchivedWorkflowsCannotBeChanged() {
        long id = idOf(create("""
                { "cronExpression": "0 8 * * *", "timezone": "UTC" }
                """));
        jdbc.sql("UPDATE workflow SET status = 'ARCHIVED' WHERE id = ?").param(workflowId).update();

        assertThat(create("""
                { "cronExpression": "0 9 * * *", "timezone": "UTC" }
                """)).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.delete(user, schedulesUri() + "/" + id)).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.get(user, schedulesUri())).hasStatusOk();
    }

    @Test
    void anotherUsersSchedulesAreNotFound() {
        long id = idOf(create("""
                { "cronExpression": "0 8 * * *", "timezone": "UTC" }
                """));
        String otherUser = new TestAccounts(mvc).newUserBearer();

        assertThat(api.get(otherUser, schedulesUri())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.post(otherUser, schedulesUri(), """
                { "cronExpression": "* * * * *", "timezone": "UTC" }
                """)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.put(otherUser, schedulesUri() + "/" + id, """
                { "cronExpression": "* * * * *", "timezone": "UTC" }
                """)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.delete(otherUser, schedulesUri() + "/" + id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult create(String json) {
        return api.post(user, schedulesUri(), json);
    }

    private String schedulesUri() {
        return "/api/workflows/" + workflowId + "/schedules";
    }
}
