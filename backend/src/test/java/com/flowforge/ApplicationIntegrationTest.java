package com.flowforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Starts the complete application against a real PostgreSQL container.
 */
@IntegrationTest
class ApplicationIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Flyway flyway;

    @Test
    void connectsToPostgres() {
        String version = jdbc.sql("select version()").query(String.class).single();

        assertThat(version).startsWith("PostgreSQL");
    }

    @Test
    void flywayHasMigratedTheDatabase() {
        boolean historyTableExists = jdbc.sql("select to_regclass('flyway_schema_history') is not null")
                .query(Boolean.class)
                .single();

        assertThat(historyTableExists).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void healthIsUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void unknownApiPathReturnsProblemDetail() {
        assertThat(mvc.get().uri("/api/does-not-exist").with(jwt()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "status": 404,
                          "title": "Not Found",
                          "instance": "/api/does-not-exist",
                          "code": "NOT_FOUND"
                        }
                        """);
    }
}
