package com.flowforge.controller;

import static com.flowforge.TestApi.idOf;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;

@IntegrationTest
class WorkflowApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    String userA;
    String userB;
    long projectId;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        TestAccounts accounts = new TestAccounts(mvc);
        userA = accounts.newUserBearer();
        userB = accounts.newUserBearer();
        projectId = api.createProject(userA, "Acme Shop Ops");
    }

    @Test
    void createsAWorkflowInDraftWithoutSteps() {
        MvcTestResult created = api.post(userA, "/api/projects/" + projectId + "/workflows", """
                { "name": "Customer onboarding", "description": "CRM and account setup" }
                """);
        long id = idOf(created);

        assertThat(created).hasStatus(HttpStatus.CREATED)
                .hasHeader(HttpHeaders.LOCATION, "/api/workflows/" + id)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "projectId": %d,
                          "name": "Customer onboarding",
                          "description": "CRM and account setup",
                          "status": "DRAFT",
                          "version": 0,
                          "steps": []
                        }
                        """.formatted(projectId));

        assertThat(api.get(userA, "/api/workflows/" + id)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("DRAFT");
    }

    @Test
    void listsTheWorkflowsOfAProjectSortedByName() {
        api.createWorkflow(userA, projectId, "beta");
        api.createWorkflow(userA, projectId, "Alpha");
        long otherProject = api.createProject(userA, "Other");
        api.createWorkflow(userA, otherProject, "Elsewhere");

        assertThat(api.get(userA, "/api/projects/" + projectId + "/workflows")).hasStatusOk()
                .bodyJson().extractingPath("$[*].name").asArray().containsExactly("Alpha", "beta");
    }

    @Test
    void updateWithTheCurrentVersionSucceedsAndIncrementsTheVersion() {
        long id = api.createWorkflow(userA, projectId, "Reports");

        assertThat(api.put(userA, "/api/workflows/" + id, """
                { "name": "Daily reports", "description": "08:00", "version": 0 }
                """))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "name": "Daily reports", "description": "08:00", "version": 1 }
                        """);
    }

    @Test
    void staleVersionIsRejectedWithVersionConflict() {
        long id = api.createWorkflow(userA, projectId, "Reports");
        assertThat(api.put(userA, "/api/workflows/" + id, """
                { "name": "Saved first", "version": 0 }
                """)).hasStatusOk();

        assertThat(api.put(userA, "/api/workflows/" + id, """
                { "name": "Saved second with an old version", "version": 0 }
                """))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("VERSION_CONFLICT");

        assertThat(api.get(userA, "/api/workflows/" + id))
                .bodyJson().extractingPath("$.name").isEqualTo("Saved first");
    }

    @Test
    void workflowNamesAreUniquePerProjectIgnoringCase() {
        api.createWorkflow(userA, projectId, "Reports");

        assertThat(api.post(userA, "/api/projects/" + projectId + "/workflows", """
                { "name": "REPORTS" }
                """))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("DUPLICATE_NAME");

        long otherProject = api.createProject(userA, "Other");
        api.createWorkflow(userA, otherProject, "Reports");
    }

    @Test
    void invalidWorkflowIsRejected() {
        assertThat(api.post(userA, "/api/projects/" + projectId + "/workflows", """
                { "name": "" }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");

        long id = api.createWorkflow(userA, projectId, "Reports");
        assertThat(api.put(userA, "/api/workflows/" + id, """
                { "name": "No version" }
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("version");
    }

    @Test
    void deletingAWorkflowAlsoDeletesItsSteps() {
        long id = api.createWorkflow(userA, projectId, "Temporary");
        assertThat(api.addHttpStep(userA, id, "fetch")).hasStatus(HttpStatus.CREATED);

        assertThat(api.delete(userA, "/api/workflows/" + id)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(api.get(userA, "/api/workflows/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        long remainingSteps = jdbc.sql("select count(*) from workflow_step where workflow_id = ?")
                .param(id).query(Long.class).single();
        assertThat(remainingSteps).isZero();
    }

    @Test
    void archivedWorkflowsAreReadOnly() {
        long id = api.createWorkflow(userA, projectId, "Old");
        jdbc.sql("update workflow set status = 'ARCHIVED' where id = ?").param(id).update();

        assertThat(api.put(userA, "/api/workflows/" + id, """
                { "name": "Renamed", "version": 0 }
                """))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATE");
        assertThat(api.addHttpStep(userA, id, "fetch"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATE");
    }

    @Test
    void anotherUsersWorkflowsAreNotFound() {
        long id = api.createWorkflow(userA, projectId, "Private");
        long stepId = idOf(api.addHttpStep(userA, id, "fetch"));

        assertThat(api.get(userB, "/api/workflows/" + id)).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        assertThat(api.put(userB, "/api/workflows/" + id, """
                { "name": "Taken over", "version": 0 }
                """)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.delete(userB, "/api/workflows/" + id)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(api.get(userB, "/api/projects/" + projectId + "/workflows")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.post(userB, "/api/projects/" + projectId + "/workflows", """
                { "name": "Planted" }
                """)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(api.addHttpStep(userB, id, "planted")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.put(userB, "/api/workflows/" + id + "/steps/" + stepId, """
                { "name": "Taken over", "config": { "method": "GET", "url": "https://evil.example.com" } }
                """)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.delete(userB, "/api/workflows/" + id + "/steps/" + stepId)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(api.get(userA, "/api/workflows/" + id)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "name": "Private", "version": 0, "steps": [ { "key": "fetch", "name": "Step fetch" } ] }
                        """);
    }
}
