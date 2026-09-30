package com.flowforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.flowforge.TestAccounts;
import com.flowforge.TestcontainersConfiguration;

@SpringBootTest(properties = {
        "flowforge.demo.email=demo@example.com",
        "flowforge.demo.password=demo-password-for-tests",
        "flowforge.demo.base-url=http://localhost:8080/"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({ "test", "demo" })
class DemoDataSeederTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DemoDataSeeder seeder;

    @Test
    void seedsADemoUserWhoCanLogInAndSeeTheExampleWorkflows() {
        var login = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "email": "demo@example.com", "password": "demo-password-for-tests" }
                        """)
                .exchange();
        assertThat(login).hasStatusOk();
        String bearer = "Bearer " + JsonPath.read(TestAccounts.body(login), "$.accessToken");

        var projects = mvc.get().uri("/api/projects").header("Authorization", bearer).exchange();
        assertThat(projects).bodyJson().extractingPath("$[*].name").asArray().containsExactly("Demo workspace");

        assertThat(workflows()).containsExactly(
                "API health check ACTIVE 1 1",
                "Daily sales report ACTIVE 1 1",
                "Partner sync with retries ACTIVE 0 1");
        assertThat(jdbc.sql("""
                SELECT config->>'url' FROM workflow_step WHERE step_key = 'fetch_orders'
                """).query(String.class).single()).isEqualTo("http://localhost:8080/demo/orders");
    }

    @Test
    void seedingAgainChangesNothing() throws Exception {
        List<String> before = workflows();

        seeder.run(null);

        assertThat(workflows()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM \"user\" WHERE email = 'demo@example.com'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theDemoEndpointsTheWorkflowsCallAreAvailable() {
        assertThat(mvc.get().uri("/demo/orders").exchange()).hasStatusOk()
                .bodyJson().extractingPath("$.count").isEqualTo(3);
    }

    private List<String> workflows() {
        return jdbc.sql("""
                SELECT w.name || ' ' || w.status || ' '
                       || (SELECT count(*) FROM workflow_schedule s WHERE s.workflow_id = w.id) || ' '
                       || (SELECT count(*) FROM workflow_execution e WHERE e.workflow_id = w.id)
                FROM workflow w JOIN project p ON p.id = w.project_id JOIN "user" u ON u.id = p.owner_id
                WHERE u.email = 'demo@example.com'
                ORDER BY w.name
                """).query(String.class).list();
    }
}
