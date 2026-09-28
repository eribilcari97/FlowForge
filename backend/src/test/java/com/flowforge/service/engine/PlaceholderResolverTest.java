package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PlaceholderResolverTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PlaceholderResolver resolver = new PlaceholderResolver();

    private final PlaceholderResolver.Context context = new PlaceholderResolver.Context(
            json("""
                    { "customer": { "email": "jane@example.com", "plan": "PRO", "vip": true }, "ids": [7, 8] }
                    """),
            91,
            12,
            Map.of(
                    "create_crm_contact", json("""
                            { "status": 201, "body": { "id": 11, "tags": ["a", "b"] } }
                            """),
                    "fetch_orders", json("""
                            { "body": { "items": [ { "id": "o-1", "total": 25.5 }, { "id": "o-2", "total": 10 } ] } }
                            """)));

    @Test
    void replacesPlaceholdersInsideText() {
        assertThat(resolve("""
                { "url": "https://crm.example.com/contacts/{{steps.create_crm_contact.output.body.id}}/notes?run={{execution.runNumber}}" }
                """))
                .isEqualTo(json("""
                        { "url": "https://crm.example.com/contacts/11/notes?run=12" }
                        """));
    }

    @Test
    void aStringThatIsOnlyAPlaceholderKeepsTheJsonType() {
        assertThat(resolve("""
                { "body": {
                    "contactId": "{{steps.create_crm_contact.output.body.id}}",
                    "customer": "{{input.customer}}",
                    "vip": "{{input.customer.vip}}",
                    "execution": "{{ execution.id }}"
                } }
                """).toString())
                .isEqualTo(json("""
                        { "body": {
                            "contactId": 11,
                            "customer": { "email": "jane@example.com", "plan": "PRO", "vip": true },
                            "vip": true,
                            "execution": 91
                        } }
                        """).toString());
    }

    @Test
    void arrayElementsAreAddressedByIndex() {
        assertThat(resolve("""
                { "first": "{{steps.fetch_orders.output.body.items.0.id}}", "text": "total {{steps.fetch_orders.output.body.items.1.total}} for {{input.ids.1}}" }
                """))
                .isEqualTo(json("""
                        { "first": "o-1", "text": "total 10 for 8" }
                        """));
    }

    @Test
    void objectsInsertedIntoTextAreWrittenAsJson() {
        assertThat(resolve("""
                { "text": "tags: {{steps.create_crm_contact.output.body.tags}}" }
                """))
                .isEqualTo(json("""
                        { "text": "tags: [\\"a\\",\\"b\\"]" }
                        """));
    }

    @Test
    void valuesWithoutPlaceholdersAreUnchanged() {
        JsonNode config = json("""
                { "method": "GET", "expectedStatus": [200, 201], "headers": { "Accept": "application/json" }, "body": null }
                """);

        assertThat(resolver.resolve(config, context)).isEqualTo(config);
    }

    @Test
    void aMissingValueFailsWithThePlaceholderName() {
        assertThatThrownBy(() -> resolve("""
                { "url": "https://x.test/{{input.customer.phone}}" }
                """))
                .isInstanceOf(PlaceholderResolver.MissingValueException.class)
                .hasMessage("No value for {{input.customer.phone}}");
        assertThatThrownBy(() -> resolve("""
                { "id": "{{steps.not_run_yet.output.body.id}}" }
                """))
                .isInstanceOf(PlaceholderResolver.MissingValueException.class);
        assertThatThrownBy(() -> resolve("""
                { "id": "{{steps.fetch_orders.output.body.items.5.id}}" }
                """))
                .isInstanceOf(PlaceholderResolver.MissingValueException.class);
    }

    @Test
    void replacementTextIsNotInterpretedAsARegexReplacement() {
        PlaceholderResolver.Context dollars = new PlaceholderResolver.Context(
                json("""
                        { "price": "$1.00 \\\\ each" }
                        """), 1, 1, Map.of());

        assertThat(resolver.resolve(json("""
                { "text": "costs {{input.price}}" }
                """), dollars))
                .isEqualTo(json("""
                        { "text": "costs $1.00 \\\\ each" }
                        """));
    }

    private JsonNode resolve(String config) {
        return resolver.resolve(json(config), context);
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }
}
