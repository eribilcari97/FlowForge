package com.flowforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.flowforge.entity.JobType;
import com.flowforge.exception.GlobalExceptionHandler.FieldError;
import com.flowforge.exception.InvalidFieldsException;

class StepConfigValidatorTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final StepConfigValidator validator =
            new StepConfigValidator(mapper, Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void acceptsAFullHttpConfig() {
        assertValid(JobType.HTTP, """
                {
                  "method": "POST",
                  "url": "https://crm.example.com/api/contacts",
                  "headers": { "Authorization": "Bearer token" },
                  "body": { "email": "{{input.customer.email}}", "tags": [1, 2] },
                  "expectedStatus": [200, 201]
                }
                """);
    }

    @Test
    void acceptsAMinimalHttpConfig() {
        assertValid(JobType.HTTP, """
                { "method": "GET", "url": "http://localhost:8080/health" }
                """);
    }

    @Test
    void acceptsPlaceholdersInsideTheUrl() {
        assertValid(JobType.HTTP, """
                { "method": "PUT", "url": "https://accounts.example.com/api/accounts/{{execution.id}}?plan={{input.plan}}" }
                """);
    }

    @Test
    void httpRequiresMethodAndUrl() {
        assertThat(errors(JobType.HTTP, "{}"))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("config.method", "config.url");
    }

    @ParameterizedTest
    @ValueSource(strings = { "ftp://example.com/file", "/relative/path", "example.com/api", "https://", "{{input.url}}" })
    void httpUrlMustBeAbsoluteHttpOrHttps(String url) {
        assertThat(errors(JobType.HTTP, """
                { "method": "GET", "url": "%s" }
                """.formatted(url)))
                .containsExactly(new FieldError("config.url", "must be an absolute http or https URL"));
    }

    @Test
    void httpRejectsUnknownMethod() {
        assertThat(errors(JobType.HTTP, """
                { "method": "FETCH", "url": "https://example.com" }
                """))
                .containsExactly(new FieldError("config.method", "has an invalid value"));
    }

    @Test
    void httpRejectsUnknownFieldsToCatchTypos() {
        assertThat(errors(JobType.HTTP, """
                { "method": "GET", "url": "https://example.com", "expectedStatuses": [200] }
                """))
                .containsExactly(new FieldError("config.expectedStatuses", "unknown field"));
    }

    @Test
    void httpExpectedStatusMustBeValidStatusCodes() {
        assertThat(errors(JobType.HTTP, """
                { "method": "GET", "url": "https://example.com", "expectedStatus": [200, 42] }
                """))
                .extracting(FieldError::field)
                .containsExactly("config.expectedStatus.1");
    }

    @Test
    void configMustBeAnObject() {
        assertThat(errors(JobType.HTTP, "[]"))
                .containsExactly(new FieldError("config", "must be a JSON object"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "PT1S", "PT30S", "PT5M", "P1D", "P7D" })
    void acceptsDelaysFromOneSecondToSevenDays(String duration) {
        assertValid(JobType.DELAY, """
                { "duration": "%s" }
                """.formatted(duration));
    }

    @ParameterizedTest
    @ValueSource(strings = { "PT0S", "PT0.5S", "P8D", "1 day", "" })
    void rejectsDelaysOutsideTheRangeOrNotIso8601(String duration) {
        assertThat(errors(JobType.DELAY, """
                { "duration": "%s" }
                """.formatted(duration)))
                .extracting(FieldError::field)
                .containsExactly("config.duration");
    }

    @Test
    void delayRejectsHttpFields() {
        assertThat(errors(JobType.DELAY, """
                { "duration": "PT5S", "url": "https://example.com" }
                """))
                .containsExactly(new FieldError("config.url", "unknown field"));
    }

    @Test
    void acceptsAValidTransformExpression() {
        assertValid(JobType.TRANSFORM, """
                { "expression": "$sum(steps.fetch_orders.output.body.orders[status='PAID'].total)" }
                """);
    }

    @Test
    void rejectsATransformExpressionThatDoesNotParse() {
        assertThat(errors(JobType.TRANSFORM, """
                { "expression": "orders[status=" }
                """))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.field()).isEqualTo("config.expression");
                    assertThat(error.message()).startsWith("is not a valid JSONata expression");
                });
    }

    @Test
    void acceptsAnEmailWithPlaceholdersInTheRecipients() {
        assertValid(JobType.EMAIL, """
                { "to": ["{{input.customer.email}}", "ops@example.com"], "subject": "Report", "html": "<p>Hi</p>" }
                """);
    }

    @Test
    void anEmailNeedsRecipientsASubjectAndABody() {
        assertThat(errors(JobType.EMAIL, """
                { "to": [], "subject": "" }
                """))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("config.to", "config.subject", "config.text");
    }

    @Test
    void emailAddressesMustBeValid() {
        assertThat(errors(JobType.EMAIL, """
                { "to": ["ops@example.com", "not an address"], "cc": ["@"], "subject": "Report", "text": "Hi" }
                """))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("config.to.1", "config.cc.0");
    }

    private void assertValid(JobType jobType, String json) {
        assertThatCode(() -> validator.validate(jobType, mapper.readTree(json))).doesNotThrowAnyException();
    }

    private List<FieldError> errors(JobType jobType, String json) {
        JsonNode config = mapper.readTree(json);
        InvalidFieldsException exception =
                catchThrowableOfType(InvalidFieldsException.class, () -> validator.validate(jobType, config));
        assertThat(exception).as("expected validation to fail").isNotNull();
        return exception.getErrors();
    }
}
