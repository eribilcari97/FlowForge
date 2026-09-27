package com.flowforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicLong;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

public class TestAccounts {

    public static final String PASSWORD = "correct-horse-battery";

    private static final AtomicLong emailCounter = new AtomicLong(System.currentTimeMillis());

    private final MockMvcTester mvc;

    public TestAccounts(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public static String uniqueEmail() {
        return "user-" + emailCounter.incrementAndGet() + "@example.com";
    }

    public void register(String email) {
        MvcTestResult result = mvc.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "email": "%s", "password": "%s", "displayName": "Test User" }
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    public String login(String email) {
        MvcTestResult result = mvc.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "email": "%s", "password": "%s" }
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(result).hasStatusOk();
        return JsonPath.read(body(result), "$.accessToken");
    }

    public String newUserBearer() {
        String email = uniqueEmail();
        register(email);
        return "Bearer " + login(email);
    }

    public static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
