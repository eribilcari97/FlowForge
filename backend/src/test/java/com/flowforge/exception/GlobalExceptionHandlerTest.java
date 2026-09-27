package com.flowforge.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the documented error format (docs/api.md §2.1) using a controller that exists only in this test.
 */
@WebMvcTest
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void invalidFieldsReturnValidationErrorWithFieldList() {
        assertThat(mvc.post().uri("/test/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "", "quantity": 11 }
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "title": "Bad Request",
                          "status": 400,
                          "detail": "Validation failed",
                          "instance": "/test/items",
                          "code": "VALIDATION_ERROR",
                          "errors": [
                            { "field": "name", "message": "must not be blank" },
                            { "field": "quantity", "message": "must be less than or equal to 10" }
                          ]
                        }
                        """);
    }

    @Test
    void unreadableJsonReturnsMalformedRequest() {
        assertThat(mvc.post().uri("/test/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void unknownJsonFieldReturnsMalformedRequest() {
        assertThat(mvc.post().uri("/test/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "widget", "quantity": 1, "colour": "red" }
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void unexpectedExceptionReturnsGenericInternalError() {
        assertThat(mvc.get().uri("/test/failure"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "status": 500,
                          "detail": "An unexpected error occurred",
                          "code": "INTERNAL_ERROR"
                        }
                        """);

        assertThat(mvc.get().uri("/test/failure"))
                .body().asString().doesNotContain("database password is hunter2");
    }

    @Test
    void unsupportedMethodFallsBackToStatusNameAsCode() {
        assertThat(mvc.delete().uri("/test/items"))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @RestController
    static class TestController {

        record Item(@NotBlank String name, @Max(10) int quantity) {
        }

        @PostMapping("/test/items")
        void create(@Valid @RequestBody Item item) {
        }

        @GetMapping("/test/failure")
        void fail() {
            throw new IllegalStateException("database password is hunter2");
        }
    }
}
