package com.flowforge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.flowforge.dto.ValidationProblem;
import com.flowforge.entity.JobType;
import com.flowforge.entity.WorkflowStep;

class WorkflowValidatorTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final WorkflowValidator validator = new WorkflowValidator(
            new StepConfigValidator(mapper, Validation.buildDefaultValidatorFactory().getValidator()));

    private final List<WorkflowStep> steps = new ArrayList<>();
    private final Map<String, List<String>> dependencies = new LinkedHashMap<>();

    @Test
    void aWorkflowWithoutStepsCannotBeActivated() {
        assertThat(validate()).containsExactly(new ValidationProblem(null, "The workflow has no steps"));
    }

    @Test
    void acceptsTheOnboardingExample() {
        httpStep("create_crm_contact", """
                { "method": "POST", "url": "https://crm.example.com/api/contacts",
                  "body": { "email": "{{input.customer.email}}" } }
                """);
        httpStep("create_account", """
                { "method": "PUT", "url": "https://accounts.example.com/api/accounts/{{execution.id}}" }
                """);
        step("wait_1_day", JobType.DELAY, """
                { "duration": "P1D" }
                """, "create_crm_contact", "create_account");
        step("notify_crm", JobType.HTTP, """
                { "method": "POST",
                  "url": "https://crm.example.com/api/contacts/{{steps.create_crm_contact.output.body.id}}/notes",
                  "body": { "text": "Account {{ steps.create_account.output.body.accountNumber }} active, run {{execution.runNumber}}" } }
                """, "wait_1_day");

        assertThat(validate()).isEmpty();
    }

    @Test
    void placeholdersMustReferenceUpstreamSteps() {
        httpStep("fetch_orders", """
                { "method": "GET", "url": "https://shop.example.com/orders" }
                """);
        httpStep("send_report", """
                { "method": "POST", "url": "https://report.example.com",
                  "body": { "orders": "{{steps.fetch_orders.output.body}}" } }
                """);

        assertThat(validate()).containsExactly(new ValidationProblem("send_report",
                "References steps.fetch_orders, which is not upstream of send_report"));
    }

    @Test
    void placeholdersMustReferenceExistingSteps() {
        httpStep("send_report", """
                { "method": "POST", "url": "https://report.example.com",
                  "headers": { "X-Order": "{{steps.fetch_order.output.body.id}}" } }
                """);

        assertThat(validate()).containsExactly(new ValidationProblem("send_report",
                "References steps.fetch_order, which does not exist"));
    }

    @Test
    void unknownPlaceholderFormsAreReported() {
        httpStep("call", """
                { "method": "GET", "url": "https://example.com/{{secret}}?now={{execution.startedAt}}" }
                """);

        assertThat(validate()).extracting(ValidationProblem::message).containsExactly(
                "Unknown placeholder {{secret}}",
                "Unknown placeholder {{execution.startedAt}}");
    }

    @Test
    void invalidStoredConfigIsReportedPerStep() {
        step("wait", JobType.DELAY, """
                { "duration": "P30D" }
                """);

        assertThat(validate()).containsExactly(new ValidationProblem("wait",
                "config.duration must be an ISO-8601 duration between PT1S and P7D"));
    }

    @Test
    void aCycleIsReported() {
        step("a", JobType.DELAY, """
                { "duration": "PT1S" }
                """, "b");
        step("b", JobType.DELAY, """
                { "duration": "PT1S" }
                """, "a");

        assertThat(validate()).extracting(ValidationProblem::message)
                .singleElement().asString().startsWith("Dependencies form a cycle:");
    }

    @Test
    void transformExpressionsMayOnlyReadUpstreamSteps() {
        httpStep("fetch_orders", """
                { "method": "GET", "url": "https://shop.example.com/orders" }
                """);
        step("revenue", JobType.TRANSFORM, """
                { "expression": "$sum(steps.fetch_orders.output.body.total) + $count(steps.refunds.output)" }
                """);

        assertThat(validate()).containsExactly(
                new ValidationProblem("revenue",
                        "Expression references steps.fetch_orders, which is not upstream of revenue"),
                new ValidationProblem("revenue", "Expression references steps.refunds, which does not exist"));
    }

    private void httpStep(String key, String config, String... dependsOn) {
        step(key, JobType.HTTP, config, dependsOn);
    }

    private void step(String key, JobType jobType, String config, String... dependsOn) {
        WorkflowStep step = new WorkflowStep(1L, key, jobType);
        step.update(key, mapper.readTree(config), 30, 3, 10);
        steps.add(step);
        dependencies.put(key, List.of(dependsOn));
    }

    private List<ValidationProblem> validate() {
        return validator.validate(steps, new WorkflowGraph(dependencies));
    }
}
