package com.flowforge.service.engine;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import com.flowforge.dto.HttpStepConfig;
import com.flowforge.entity.JobType;

@Component
public class HttpJobHandler implements JobHandler {

    public static final int MAX_OUTPUT_BYTES = 262_144;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> KEPT_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public JobType type() {
        return JobType.HTTP;
    }

    @Override
    public JobResult execute(JsonNode configNode, int timeoutSeconds) {
        HttpStepConfig config;
        URI uri;
        try {
            config = objectMapper.treeToValue(configNode, HttpStepConfig.class);
            uri = new URI(config.url());
        } catch (JacksonException | URISyntaxException e) {
            return new JobResult.Failure(ErrorType.INVALID_CONFIG, "Invalid HTTP configuration: " + e.getMessage());
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return new JobResult.Failure(ErrorType.INVALID_CONFIG, "Not an absolute URL: " + config.url());
        }

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();

        long startedAt = System.nanoTime();
        try {
            RestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(config.method().name())).uri(uri);
            if (config.headers() != null) {
                config.headers().forEach(request::header);
            }
            if (config.body() != null && !config.body().isNull()) {
                if (config.headers() == null || config.headers().keySet().stream()
                        .noneMatch(HttpHeaders.CONTENT_TYPE::equalsIgnoreCase)) {
                    request.contentType(MediaType.APPLICATION_JSON);
                }
                request.body(objectMapper.writeValueAsString(config.body()));
            }
            return request.exchange((clientRequest, response) -> toResult(response, config, startedAt));
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                return new JobResult.Failure(ErrorType.TIMEOUT, "No response within " + timeoutSeconds + " s");
            }
            return new JobResult.Failure(ErrorType.CONNECTION_ERROR, "Could not connect: " + rootMessage(e));
        }
    }

    private JobResult toResult(ClientHttpResponse response, HttpStepConfig config, long startedAt)
            throws IOException {
        int status = response.getStatusCode().value();
        byte[] body = response.getBody().readNBytes(MAX_OUTPUT_BYTES + 1);
        if (body.length > MAX_OUTPUT_BYTES) {
            return new JobResult.Failure(ErrorType.OUTPUT_TOO_LARGE, "Response body is larger than 256 KB");
        }
        if (!isExpected(status, config.expectedStatus())) {
            return new JobResult.Failure(errorTypeFor(status), describe(status));
        }

        ObjectNode headers = JsonNodeFactory.instance.objectNode();
        for (String name : KEPT_HEADERS) {
            String value = response.getHeaders().getFirst(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("status", status);
        output.set("headers", headers);
        output.set("body", parseBody(body));
        output.put("durationMs", (System.nanoTime() - startedAt) / 1_000_000);
        return new JobResult.Success(output);
    }

    private JsonNode parseBody(byte[] body) {
        if (body.length == 0) {
            return JsonNodeFactory.instance.nullNode();
        }
        String text = new String(body, StandardCharsets.UTF_8);
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readTree(text);
            } catch (JacksonException e) {
                return JsonNodeFactory.instance.stringNode(text);
            }
        }
        return JsonNodeFactory.instance.stringNode(text);
    }

    private static boolean isExpected(int status, List<Integer> expectedStatus) {
        if (expectedStatus == null || expectedStatus.isEmpty()) {
            return status >= 200 && status < 300;
        }
        return expectedStatus.contains(status);
    }

    private static ErrorType errorTypeFor(int status) {
        if (status == 429) {
            return ErrorType.HTTP_429;
        }
        if (status >= 500) {
            return ErrorType.HTTP_5XX;
        }
        if (status >= 400) {
            return ErrorType.HTTP_4XX;
        }
        return ErrorType.UNEXPECTED_STATUS;
    }

    private static String describe(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known != null ? "HTTP " + status + " " + known.getReasonPhrase() : "HTTP " + status;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
