package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransformJobHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TransformJobHandler handler = new TransformJobHandler(mapper);

    private final JsonNode data = mapper.readTree("""
            {
              "input": { "currency": "EUR", "vat": 0.19 },
              "steps": {
                "fetch_orders": { "output": { "status": 200, "body": { "orders": [
                  { "id": "o-1001", "total": 25.50, "status": "PAID", "customer": "jane@example.com" },
                  { "id": "o-1002", "total": 99.00, "status": "PAID", "customer": "ali@example.com" },
                  { "id": "o-1003", "total": 12.25, "status": "REFUNDED", "customer": "jane@example.com" }
                ] } } }
              },
              "execution": { "id": 91, "runNumber": 12 }
            }
            """);

    @Test
    void filtersOrders() {
        assertThat(evaluate("steps.fetch_orders.output.body.orders[status='PAID'].id"))
                .isEqualTo(json("[\"o-1001\", \"o-1002\"]"));
    }

    @Test
    void aggregatesAndCounts() {
        assertThat(evaluate("""
                {
                  "revenue": $sum(steps.fetch_orders.output.body.orders[status='PAID'].total),
                  "orders": $count(steps.fetch_orders.output.body.orders),
                  "refunds": $count(steps.fetch_orders.output.body.orders[status='REFUNDED'])
                }
                """))
                .isEqualTo(json("{ \"revenue\": 124.5, \"orders\": 3, \"refunds\": 1 }"));
    }

    @Test
    void doesArithmeticWithTheInput() {
        assertThat(evaluate("$round($sum(steps.fetch_orders.output.body.orders[status='PAID'].total) * (1 + input.vat), 2)")
                .doubleValue()).isEqualTo(148.16);
    }

    @Test
    void groupsByAField() {
        assertThat(evaluate("steps.fetch_orders.output.body.orders{ customer: $sum(total) }").toString())
                .isEqualTo("{\"jane@example.com\":37.75,\"ali@example.com\":99}");
    }

    @Test
    void usesExecutionFields() {
        assertThat(evaluate("'Report #' & execution.runNumber & ' in ' & input.currency").stringValue())
                .isEqualTo("Report #12 in EUR");
    }

    @Test
    void missingFieldsAreLeftOutInsteadOfFailing() {
        assertThat(evaluate("steps.fetch_orders.output.body.orders.discount").isNull()).isTrue();
        assertThat(evaluate("{ 'discounts': steps.fetch_orders.output.body.orders.discount, 'count': 3 }"))
                .isEqualTo(json("{ \"count\": 3 }"));
        assertThat(evaluate("$sum(steps.fetch_orders.output.body.orders[status='PENDING'].total)").isNull())
                .as("nothing matched, so the sum is undefined")
                .isTrue();
        assertThat(evaluate("$sum([steps.fetch_orders.output.body.orders[status='PENDING'].total])").intValue())
                .as("wrapping the path in [] turns 'nothing' into an empty array")
                .isZero();
    }

    @Test
    void anInvalidExpressionFailsWithTheParserMessage() {
        JobResult result = handler.execute(json("{ \"expression\": \"orders[status=\" }"), context(5));

        assertThat(result).isInstanceOfSatisfying(JobResult.Failure.class, failure -> {
            assertThat(failure.type()).isEqualTo(ErrorType.TRANSFORM_ERROR);
            assertThat(failure.message()).startsWith("Invalid expression:");
        });
    }

    @Test
    void aRunawayExpressionIsStoppedByTheTimeout() {
        long startedAt = System.nanoTime();

        JobResult result = handler.execute(
                json("{ \"expression\": \"($f := function($n) { $f($n + 1) }; $f(0))\" }"), context(1));

        assertThat(result).isInstanceOfSatisfying(JobResult.Failure.class,
                failure -> assertThat(failure.type()).isEqualTo(ErrorType.TRANSFORM_ERROR));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
    }

    private JsonNode evaluate(String expression) {
        JobResult result = handler.execute(mapper.createObjectNode().put("expression", expression), context(5));
        assertThat(result).isInstanceOf(JobResult.Success.class);
        return ((JobResult.Success) result).output();
    }

    private JobContext context(int timeoutSeconds) {
        return new JobContext(301, 1, timeoutSeconds, data);
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }
}
