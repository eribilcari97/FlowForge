package com.flowforge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class MailpitContainer extends GenericContainer<MailpitContainer> {

    private static final int SMTP_PORT = 1025;
    private static final int API_PORT = 8025;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public record Message(String subject, List<String> to, List<String> cc, String from, String text, String html) {
    }

    public MailpitContainer() {
        super("axllent/mailpit:v1.31");
        withExposedPorts(SMTP_PORT, API_PORT);
        waitingFor(Wait.forHttp("/api/v1/info").forPort(API_PORT));
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    public List<Message> messages() {
        List<Message> messages = new ArrayList<>();
        for (JsonNode summary : call("GET", "/api/v1/messages").path("messages")) {
            JsonNode message = call("GET", "/api/v1/message/" + summary.path("ID").asString());
            messages.add(new Message(
                    message.path("Subject").asString(),
                    addresses(message.path("To")),
                    addresses(message.path("Cc")),
                    message.path("From").path("Address").asString(),
                    message.path("Text").asString().trim(),
                    message.path("HTML").asString().trim()));
        }
        return messages;
    }

    public void deleteAllMessages() {
        send("DELETE", "/api/v1/messages");
    }

    private static List<String> addresses(JsonNode list) {
        List<String> addresses = new ArrayList<>();
        list.forEach(entry -> addresses.add(entry.path("Address").asString()));
        return addresses;
    }

    private JsonNode call(String method, String path) {
        return mapper.readTree(send(method, path));
    }

    private String send(String method, String path) {
        URI uri = URI.create("http://" + getHost() + ":" + getMappedPort(API_PORT) + path);
        HttpRequest request = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit API call failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
