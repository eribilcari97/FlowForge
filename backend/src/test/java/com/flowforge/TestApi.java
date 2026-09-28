package com.flowforge;

import static com.flowforge.TestAccounts.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

public class TestApi {

    private final MockMvcTester mvc;

    public TestApi(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public static long idOf(MvcTestResult result) {
        Number id = JsonPath.read(body(result), "$.id");
        return id.longValue();
    }

    public long createProject(String auth, String name) {
        MvcTestResult result = post(auth, "/api/projects", """
                { "name": "%s" }
                """.formatted(name));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return idOf(result);
    }

    public long createWorkflow(String auth, long projectId, String name) {
        MvcTestResult result = post(auth, "/api/projects/" + projectId + "/workflows", """
                { "name": "%s" }
                """.formatted(name));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return idOf(result);
    }

    public MvcTestResult addHttpStep(String auth, long workflowId, String key) {
        return post(auth, "/api/workflows/" + workflowId + "/steps", """
                {
                  "key": "%s",
                  "name": "Step %s",
                  "jobType": "HTTP",
                  "config": { "method": "GET", "url": "https://example.com/%s" }
                }
                """.formatted(key, key, key));
    }

    public MvcTestResult post(String auth, String uri, String json) {
        return mvc.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    public MvcTestResult put(String auth, String uri, String json) {
        return mvc.put().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    public MvcTestResult get(String auth, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, auth).exchange();
    }

    public MvcTestResult delete(String auth, String uri) {
        return mvc.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, auth).exchange();
    }
}
