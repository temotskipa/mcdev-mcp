package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RecordInputSchemaFactory implements InputSchemaFactory {
    private static final RecordInputSchemaFactory STANDARD = new RecordInputSchemaFactory();

    private RecordInputSchemaFactory() {
    }

    public static RecordInputSchemaFactory standard() {
        return STANDARD;
    }

    @Override
    public JsonObjectSchema generate(JsonType<?> type) {
        Class<?> recordType = Objects.requireNonNull(type, "type").rawClass();
        if (recordType == null || !recordType.isRecord()) {
            throw new IllegalArgumentException("Tool input roots must be records");
        }
        return JsonObjectSchema.of(objectSchema(recordType, new HashSet<>()));
    }

    private static Map<String, Object> schemaFor(Type type, Set<Class<?>> activeRecords) {
        if (type instanceof WildcardType || type instanceof TypeVariable<?>) {
            throw new IllegalArgumentException("Wildcard and type-variable input components are unsupported: " + type.getTypeName());
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return collectionSchema(parameterizedType, activeRecords);
        }
        if (!(type instanceof Class<?> componentType)) {
            throw new IllegalArgumentException("Unsupported input component type: " + type.getTypeName());
        }
        if (componentType == Object.class || Map.class.isAssignableFrom(componentType)) {
            throw new IllegalArgumentException("Unsupported input component type: " + componentType.getTypeName());
        }
        if (Collection.class.isAssignableFrom(componentType)) {
            throw new IllegalArgumentException("Raw collection input components are unsupported: " + componentType.getTypeName());
        }
        if (componentType.isArray()) {
            return arraySchema(schemaFor(componentType.getComponentType(), activeRecords));
        }
        if (componentType == String.class) {
            return schemaWithType("string");
        }
        if (componentType == boolean.class || componentType == Boolean.class) {
            return schemaWithType("boolean");
        }
        if (isIntegral(componentType)) {
            return schemaWithType("integer");
        }
        if (isDecimal(componentType)) {
            return schemaWithType("number");
        }
        if (componentType.isEnum()) {
            Map<String, Object> schema = schemaWithType("string");
            List<String> values = new ArrayList<>();
            for (Object constant : componentType.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            schema.put("enum", values);
            return schema;
        }
        if (componentType.isRecord()) {
            return objectSchema(componentType, activeRecords);
        }
        throw new IllegalArgumentException("Unsupported input component type: " + componentType.getTypeName());
    }

    private static Map<String, Object> collectionSchema(ParameterizedType type, Set<Class<?>> activeRecords) {
        if (!(type.getRawType() instanceof Class<?> rawType) || !Collection.class.isAssignableFrom(rawType)) {
            throw new IllegalArgumentException("Unsupported parameterized input component type: " + type.getTypeName());
        }
        Type[] arguments = type.getActualTypeArguments();
        if (arguments.length != 1 || arguments[0] instanceof ParameterizedType) {
            throw new IllegalArgumentException("Unsupported parameterized input component type: " + type.getTypeName());
        }
        return arraySchema(schemaFor(arguments[0], activeRecords));
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> schema = schemaWithType("array");
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> objectSchema(Class<?> recordType, Set<Class<?>> activeRecords) {
        if (!activeRecords.add(recordType)) {
            throw new IllegalArgumentException("Recursive input records are unsupported: " + recordType.getTypeName());
        }
        try {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : recordType.getRecordComponents()) {
                String propertyName = propertyName(component);
                if (properties.containsKey(propertyName)) {
                    throw new IllegalArgumentException("Duplicate input property name: " + propertyName);
                }
                Map<String, Object> propertySchema = schemaFor(component.getGenericType(), activeRecords);
                applyMetadata(component.getAnnotation(InputProperty.class), propertySchema);
                properties.put(propertyName, propertySchema);
                if (component.isAnnotationPresent(InputProperty.class) && component.getAnnotation(InputProperty.class).required()) {
                    required.add(propertyName);
                }
            }
            Map<String, Object> schema = schemaWithType("object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        } finally {
            activeRecords.remove(recordType);
        }
    }

    private static String propertyName(RecordComponent component) {
        JsonProperty annotation = component.getAnnotation(JsonProperty.class);
        if (annotation == null) {
            annotation = component.getAccessor().getAnnotation(JsonProperty.class);
        }
        return annotation == null || annotation.value().isEmpty() ? component.getName() : annotation.value();
    }

    private static void applyMetadata(InputProperty metadata, Map<String, Object> schema) {
        if (metadata == null) {
            return;
        }
        if (!metadata.description().isEmpty()) {
            schema.put("description", metadata.description());
        }
        if ((!metadata.minimum().isEmpty() || !metadata.maximum().isEmpty())
                && !"integer".equals(schema.get("type")) && !"number".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Bounds are only supported for numeric input properties");
        }
        if (!metadata.minimum().isEmpty()) {
            schema.put("minimum", parseDecimal(metadata.minimum(), "minimum"));
        }
        if (!metadata.maximum().isEmpty()) {
            schema.put("maximum", parseDecimal(metadata.maximum(), "maximum"));
        }
        if (schema.containsKey("minimum") && schema.containsKey("maximum")
                && ((BigDecimal) schema.get("maximum")).compareTo((BigDecimal) schema.get("minimum")) < 0) {
            throw new IllegalArgumentException("Input property maximum must not be below minimum");
        }
        if (!metadata.defaultValue().isEmpty()) {
            schema.put("default", parseDefault(metadata.defaultValue(), schema));
        }
    }

    private static BigDecimal parseDecimal(String value, String metadataName) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + metadataName + " decimal: " + value, exception);
        }
    }

    private static Object parseDefault(String value, Map<String, Object> schema) {
        String type = (String) schema.get("type");
        return switch (type) {
            case "string" -> value;
            case "boolean" -> parseBoolean(value);
            case "integer" -> parseInteger(value);
            case "number" -> parseDecimal(value, "default");
            default -> throw new IllegalArgumentException("Defaults are unsupported for input property type: " + type);
        };
    }

    private static boolean parseBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("Invalid boolean default: " + value);
        }
        return Boolean.parseBoolean(value);
    }

    private static BigInteger parseInteger(String value) {
        try {
            return new BigDecimal(value).toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid integer default: " + value, exception);
        }
    }

    private static boolean isIntegral(Class<?> type) {
        return type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == BigInteger.class;
    }

    private static boolean isDecimal(Class<?> type) {
        return type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == BigDecimal.class;
    }

    private static Map<String, Object> schemaWithType(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        return schema;
    }
}
