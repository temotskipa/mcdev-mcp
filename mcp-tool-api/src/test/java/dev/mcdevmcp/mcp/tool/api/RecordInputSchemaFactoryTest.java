package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordInputSchemaFactoryTest {
    @Test
    void generatesADeeplyImmutableSchemaForAnnotatedRecordComponents() {
        JsonObjectSchema schema = RecordInputSchemaFactory.standard().generate(JsonType.of(SchemaInput.class));

        assertEquals(List.of("type", "properties", "required"), List.copyOf(schema.value().keySet()));
        assertEquals(
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Search text", "default", "all"),
                                "includeDetails", Map.of("type", "boolean", "default", true),
                                "threshold", Map.of(
                                        "type", "number",
                                        "minimum", new BigDecimal("0.25"),
                                        "maximum", new BigDecimal("4.50"),
                                        "default", new BigDecimal("1.50")
                                ),
                                "mode", Map.of("type", "string", "enum", List.of("FAST", "THOROUGH"), "default", "THOROUGH"),
                                "limit", Map.of("type", "integer", "default", new BigInteger("42")),
                                "optionalFilter", Map.of("type", "string")
                        ),
                        "required", List.of("query")
                ),
                schema.value()
        );

        Object propertiesValue = schema.value().get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            throw new AssertionError("properties must be an object");
        }
        assertEquals(List.of("query", "includeDetails", "threshold", "mode", "limit", "optionalFilter"), List.copyOf(properties.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> schema.value().put("additionalProperties", false));
        assertThrows(UnsupportedOperationException.class, properties::clear);
        Object modeValue = properties.get("mode");
        if (!(modeValue instanceof Map<?, ?> mode)) {
            throw new AssertionError("mode must be an object");
        }
        Object enumValue = mode.get("enum");
        if (!(enumValue instanceof List<?> enumValues)) {
            throw new AssertionError("enum must be an array");
        }
        assertThrows(UnsupportedOperationException.class, () -> enumValues.add(null));
    }

    @Test
    void rejectsAmbiguousAndUnsupportedInputTypesAndMetadata() {
        InputSchemaFactory factory = RecordInputSchemaFactory.standard();

        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(String.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(ObjectComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(MapComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(RawListComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(WildcardListComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(NestedListComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(DuplicatePropertyInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(InvalidMinimumInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(ReversedBoundsInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(NonNumericBoundsInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(JsonValueWithoutDelegatingCreatorInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(InvalidDelegatingCreatorInput.class)));
    }

    private record ObjectComponentInput(Object value) {
    }

    private record MapComponentInput(Map<String, String> values) {
    }

    @SuppressWarnings("rawtypes")
    private record RawListComponentInput(List values) {
    }

    private record WildcardListComponentInput(List<?> values) {
    }

    private record NestedListComponentInput(List<List<String>> values) {
    }

    private record DuplicatePropertyInput(@JsonProperty("same") String first, @JsonProperty("same") String second) {
    }

    private record InvalidMinimumInput(@InputProperty(minimum = "not-a-number") BigDecimal value) {
    }

    private record ReversedBoundsInput(@InputProperty(minimum = "5", maximum = "4") BigDecimal value) {
    }

    private record NonNumericBoundsInput(@InputProperty(minimum = "1") String value) {
    }

    private record JsonValueWithoutDelegatingCreatorInput(JsonValueWithoutDelegatingCreator value) {
    }

    private record JsonValueWithoutDelegatingCreator(String value) {
        @com.fasterxml.jackson.annotation.JsonValue
        String wireValue() {
            return value;
        }
    }

    private record InvalidDelegatingCreatorInput(InvalidDelegatingCreator value) {
    }

    private record InvalidDelegatingCreator(String first, String second) {
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        InvalidDelegatingCreator {
        }
    }
}
