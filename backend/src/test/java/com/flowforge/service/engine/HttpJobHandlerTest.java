package com.flowforge.service.engine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpJobHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpJobHandler handler = new HttpJobHandler(mapper, new AddressGuard(new OutboundHttpProperties(true, List.of())));
    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void sendsMethodHeadersAndJsonBodyAndReturnsTheParsedResponse() {
        server.stubFor(post("/contacts").willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withHeader("Location", "/contacts/11")
                .withBody("""
                        { "id": 11 }
                        """)));

        JobResult result = execute("""
                { "method": "POST", "url": "%s/contacts", "headers": { "X-Api-Key": "secret" },
                  "body": { "email": "jane@example.com" }, "expectedStatus": [201] }
                """, 5);

        assertThat(result).isInstanceOfSatisfying(JobResult.Success.class, success -> {
            JsonNode output = success.output();
            assertThat(output.get("status").intValue()).isEqualTo(201);
            assertThat(output.at("/body/id").intValue()).isEqualTo(11);
            assertThat(output.at("/headers/Location").stringValue()).isEqualTo("/contacts/11");
            assertThat(output.get("durationMs").longValue()).isNotNegative();
        });
        server.verify(postRequestedFor(urlEqualTo("/contacts"))
                .withHeader("X-Api-Key", equalTo("secret"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        { "email": "jane@example.com" }
                        """)));
    }

    @Test
    void aTextBodyIsKeptAsText() {
        server.stubFor(get("/health").willReturn(aResponse().withStatus(200).withBody("OK")));

        JobResult result = execute("""
                { "method": "GET", "url": "%s/health" }
                """, 5);

        assertThat(result).isInstanceOfSatisfying(JobResult.Success.class,
                success -> assertThat(success.output().get("body").stringValue()).isEqualTo("OK"));
    }

    @Test
    void errorStatusesAreClassified() {
        server.stubFor(get("/down").willReturn(aResponse().withStatus(503)));
        server.stubFor(get("/missing").willReturn(aResponse().withStatus(404)));
        server.stubFor(get("/busy").willReturn(aResponse().withStatus(429)));

        assertThat(execute("""
                { "method": "GET", "url": "%s/down" }
                """, 5)).isEqualTo(new JobResult.Failure(ErrorType.HTTP_5XX, "HTTP 503 Service Unavailable"));
        assertThat(execute("""
                { "method": "GET", "url": "%s/missing" }
                """, 5)).isEqualTo(new JobResult.Failure(ErrorType.HTTP_4XX, "HTTP 404 Not Found"));
        assertThat(execute("""
                { "method": "GET", "url": "%s/busy" }
                """, 5)).isEqualTo(new JobResult.Failure(ErrorType.HTTP_429, "HTTP 429 Too Many Requests"));
    }

    @Test
    void aSuccessStatusThatIsNotExpectedFails() {
        server.stubFor(post("/contacts").willReturn(aResponse().withStatus(200)));

        assertThat(execute("""
                { "method": "POST", "url": "%s/contacts", "expectedStatus": [201] }
                """, 5)).isEqualTo(new JobResult.Failure(ErrorType.UNEXPECTED_STATUS, "HTTP 200 OK"));
    }

    @Test
    void aSlowResponseIsATimeout() {
        server.stubFor(get("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

        assertThat(execute("""
                { "method": "GET", "url": "%s/slow" }
                """, 1)).isEqualTo(new JobResult.Failure(ErrorType.TIMEOUT, "No response within 1 s"));
    }

    @Test
    void anUnreachableServerIsAConnectionError() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        JobResult result = handler.execute(mapper.readTree("""
                { "method": "GET", "url": "http://localhost:%d/nothing" }
                """.formatted(closedPort)), new JobContext(301, 1, 5, null));

        assertThat(result).isInstanceOfSatisfying(JobResult.Failure.class,
                failure -> assertThat(failure.type()).isEqualTo(ErrorType.CONNECTION_ERROR));
    }

    @Test
    void aResponseLargerThan256KbFails() {
        server.stubFor(get("/huge").willReturn(aResponse().withStatus(200)
                .withBody("x".repeat(HttpJobHandler.MAX_OUTPUT_BYTES + 1))));

        assertThat(execute("""
                { "method": "GET", "url": "%s/huge" }
                """, 5)).isEqualTo(new JobResult.Failure(ErrorType.OUTPUT_TOO_LARGE, "Response body is larger than 256 KB"));
    }

    @Test
    void aUrlThatBecameInvalidAfterPlaceholdersFails() {
        JobResult result = execute("""
                { "method": "GET", "url": "%s/search?q=two words" }
                """, 5);

        assertThat(result).isInstanceOfSatisfying(JobResult.Failure.class,
                failure -> assertThat(failure.type()).isEqualTo(ErrorType.INVALID_CONFIG));
    }

    @Test
    void everyRequestCarriesTheJobIdAsIdempotencyKey() {
        server.stubFor(post("/payments").willReturn(aResponse().withStatus(201)));

        execute("""
                { "method": "POST", "url": "%s/payments" }
                """, 5);

        server.verify(postRequestedFor(urlEqualTo("/payments")).withHeader("Idempotency-Key", equalTo("301")));
    }

    @Test
    void anIdempotencyKeyFromTheStepConfigIsKept() {
        server.stubFor(post("/payments").willReturn(aResponse().withStatus(201)));

        execute("""
                { "method": "POST", "url": "%s/payments", "headers": { "idempotency-key": "order-7" } }
                """, 5);

        server.verify(postRequestedFor(urlEqualTo("/payments")).withHeader("Idempotency-Key", equalTo("order-7")));
    }

    @Test
    void onlyMethodsThatCanBeRepeatedSafelyAreSafeToRepeat() {
        assertThat(handler.isSafeToRepeat(mapper.readTree("{ \"method\": \"GET\" }"))).isTrue();
        assertThat(handler.isSafeToRepeat(mapper.readTree("{ \"method\": \"PUT\" }"))).isTrue();
        assertThat(handler.isSafeToRepeat(mapper.readTree("{ \"method\": \"DELETE\" }"))).isTrue();
        assertThat(handler.isSafeToRepeat(mapper.readTree("{ \"method\": \"POST\" }"))).isFalse();
        assertThat(handler.isSafeToRepeat(mapper.readTree("{ \"method\": \"PATCH\" }"))).isFalse();
    }

    @Test
    void aRequestToAPrivateAddressIsBlockedBeforeItIsSent() {
        server.stubFor(get("/internal").willReturn(aResponse().withStatus(200)));
        HttpJobHandler guarded = new HttpJobHandler(mapper,
                new AddressGuard(new OutboundHttpProperties(false, List.of())));

        JobResult result = guarded.execute(mapper.readTree("""
                { "method": "GET", "url": "%s/internal" }
                """.formatted(server.baseUrl())), new JobContext(301, 1, 5, null));

        assertThat(result).isInstanceOfSatisfying(JobResult.Failure.class, failure -> {
            assertThat(failure.type()).isEqualTo(ErrorType.BLOCKED_ADDRESS);
            assertThat(failure.message()).contains("resolves to 127.0.0.1");
        });
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private JobResult execute(String configTemplate, int timeoutSeconds) {
        return handler.execute(mapper.readTree(configTemplate.formatted(server.baseUrl())),
                new JobContext(301, 1, timeoutSeconds, null));
    }
}
