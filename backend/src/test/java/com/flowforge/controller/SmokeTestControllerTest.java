package com.flowforge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(SmokeTestController.class)
class SmokeTestControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void apiHealthConfirmsTheBackendIsRunning() {
        assertThat(mvc.get().uri("/api/health"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .isStrictlyEqualTo("""
                        { "status": "UP", "message": "FlowForge backend is running" }
                        """);
    }
}
