package com.flowforge.controller;

import static com.flowforge.TestAccounts.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;

@IntegrationTest
class ProjectApiTest {

    @Autowired
    MockMvcTester mvc;

    String userA;
    String userB;

    @BeforeEach
    void setUp() {
        TestAccounts accounts = new TestAccounts(mvc);
        userA = accounts.newUserBearer();
        userB = accounts.newUserBearer();
    }

    @Test
    void projectLifecycle() {
        MvcTestResult created = create(userA, "Acme Shop Ops", "Reports and monitoring");
        long id = idOf(created);

        assertThat(created).hasStatus(HttpStatus.CREATED)
                .hasHeader(HttpHeaders.LOCATION, "/api/projects/" + id)
                .bodyJson().isLenientlyEqualTo("""
                        { "name": "Acme Shop Ops", "description": "Reports and monitoring", "workflowCount": 0 }
                        """);
        assertThat(JsonPath.<String>read(body(created), "$.createdAt")).isNotBlank();

        assertThat(get(userA, id)).hasStatusOk()
                .bodyJson().extractingPath("$.name").isEqualTo("Acme Shop Ops");

        assertThat(update(userA, id, "Acme Operations", null)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "id": %d, "name": "Acme Operations", "description": null }
                        """.formatted(id));

        assertThat(delete(userA, id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(userA, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void listContainsOnlyTheUsersOwnProjectsSortedByName() {
        create(userA, "beta", null);
        create(userA, "Alpha", null);
        create(userB, "B's project", null);

        assertThat(mvc.get().uri("/api/projects").header(HttpHeaders.AUTHORIZATION, userA))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].name").asArray().containsExactly("Alpha", "beta");
    }

    @Test
    void projectNamesAreUniquePerUserIgnoringCase() {
        create(userA, "Reports", null);

        assertThat(create(userA, "REPORTS", null))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("DUPLICATE_NAME");

        assertThat(create(userB, "Reports", null)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void renamingToAnExistingNameIsRejectedButKeepingTheOwnNameIsAllowed() {
        create(userA, "Existing", null);
        long id = idOf(create(userA, "Other", null));

        assertThat(update(userA, id, "existing", null)).hasStatus(HttpStatus.CONFLICT);
        assertThat(update(userA, id, "Other", "new description")).hasStatusOk();
        assertThat(update(userA, id, "OTHER", null)).hasStatusOk();
    }

    @Test
    void invalidProjectIsRejected() {
        assertThat(create(userA, " ", "x".repeat(1001)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("name", "description");
    }

    @Test
    void anotherUsersProjectIsIndistinguishableFromAMissingOne() {
        long id = idOf(create(userA, "Private", "A's data"));
        long missingId = Long.MAX_VALUE;

        MvcTestResult foreign = get(userB, id);
        MvcTestResult missing = get(userB, missingId);

        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().isLenientlyEqualTo("""
                        { "status": 404, "code": "NOT_FOUND", "detail": "Project not found" }
                        """);
        assertThat(body(foreign)).doesNotContain("Private").doesNotContain("A's data");
        assertThat(body(foreign).replace(String.valueOf(id), "{id}"))
                .isEqualTo(body(missing).replace(String.valueOf(missingId), "{id}"));

        assertThat(update(userB, id, "Taken over", null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(userB, id)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(get(userA, id)).hasStatusOk()
                .bodyJson().extractingPath("$.name").isEqualTo("Private");
    }

    @Test
    void projectEndpointsRequireAToken() {
        assertThat(mvc.get().uri("/api/projects"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
        assertThat(mvc.post().uri("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
                { "name": "Anonymous" }
                """)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult create(String auth, String name, String description) {
        return mvc.post().uri("/api/projects")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(name, description))
                .exchange();
    }

    private MvcTestResult get(String auth, long id) {
        return mvc.get().uri("/api/projects/{id}", id).header(HttpHeaders.AUTHORIZATION, auth).exchange();
    }

    private MvcTestResult update(String auth, long id, String name, String description) {
        return mvc.put().uri("/api/projects/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(name, description))
                .exchange();
    }

    private MvcTestResult delete(String auth, long id) {
        return mvc.delete().uri("/api/projects/{id}", id).header(HttpHeaders.AUTHORIZATION, auth).exchange();
    }

    private static long idOf(MvcTestResult result) {
        Number id = JsonPath.read(body(result), "$.id");
        return id.longValue();
    }

    private static String json(String name, String description) {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return """
                { "name": "%s", "description": %s }
                """.formatted(name, descriptionJson);
    }
}
