package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

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
        if (componentType.isEnum()) return enumSchema(componentType, activeRecords);
        if (componentType.isRecord()) {
            return recordSchema(componentType, activeRecords);
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

    private static Map<String, Object> recordSchema(Class<?> recordType, Set<Class<?>> activeRecords) {
        Type delegatingType = delegatingCreatorType(recordType);
        JsonValueAccessor jsonValue = jsonValueAccessor(recordType);
        if (delegatingType == null) {
            if (jsonValue != null) {
                throw new IllegalArgumentException("JsonValue records require one delegating JsonCreator: " + recordType.getTypeName());
            }
            return objectSchema(recordType, activeRecords);
        }
        Map<String, Object> schema = schemaFor(delegatingType, activeRecords);
        if (jsonValue != null && !schema.get("type").equals(schemaFor(jsonValue.type(), activeRecords).get("type"))) {
            throw new IllegalArgumentException("JsonValue type must match the delegating JsonCreator input: " + recordType.getTypeName());
        }
        return schema;
    }

    private static Map<String, Object> enumSchema(Class<?> enumType, Set<Class<?>> activeRecords) {
        Type delegatingType = delegatingCreatorType(enumType);
        JsonValueAccessor jsonValue = jsonValueAccessor(enumType);
        if (delegatingType != null && jsonValue == null) {
            throw new IllegalArgumentException("Enums with JsonCreator require JsonValue schema metadata: " + enumType.getTypeName());
        }
        Map<String, Object> schema = jsonValue == null ? schemaWithType("string") : schemaFor(jsonValue.type(), activeRecords);
        List<Object> values = new ArrayList<>();
        Set<Object> wireValues = new HashSet<>();
        for (Object constant : enumType.getEnumConstants()) {
            Object wireValue = jsonValue == null ? enumPropertyName((Enum<?>) constant) : jsonValue.read(constant);
            if (!wireValues.add(wireValue)) {
                throw new IllegalArgumentException("Duplicate effective enum wire value: " + wireValue + " for " + enumType.getTypeName());
            }
            values.add(wireValue);
        }
        schema.put("enum", values);
        return schema;
    }

    private static String enumPropertyName(Enum<?> constant) {
        try {
            JsonProperty annotation = constant.getDeclaringClass().getField(constant.name()).getAnnotation(JsonProperty.class);
            return annotation == null || annotation.value().isEmpty() ? constant.name() : annotation.value();
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Enum constant field was not found: " + constant.name(), exception);
        }
    }

    private static Type delegatingCreatorType(Class<?> type) {
        List<Type> creatorTypes = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            JsonCreator annotation = constructor.getAnnotation(JsonCreator.class);
            if (annotation != null) {
                creatorTypes.add(creatorParameterType(type, annotation, constructor.getParameterCount(), constructor.getGenericParameterTypes()));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            JsonCreator annotation = method.getAnnotation(JsonCreator.class);
            if (annotation != null) {
                if (!Modifier.isStatic(method.getModifiers()) || !type.isAssignableFrom(method.getReturnType())) {
                    throw new IllegalArgumentException("JsonCreator factory must be static and return " + type.getTypeName());
                }
                creatorTypes.add(creatorParameterType(type, annotation, method.getParameterCount(), method.getGenericParameterTypes()));
            }
        }
        if (creatorTypes.size() > 1) {
            throw new IllegalArgumentException("Only one JsonCreator is supported for input type: " + type.getTypeName());
        }
        return creatorTypes.isEmpty() ? null : creatorTypes.getFirst();
    }

    private static Type creatorParameterType(Class<?> type, JsonCreator annotation, int parameterCount, Type[] parameterTypes) {
        if (annotation.mode() != JsonCreator.Mode.DELEGATING || parameterCount != 1) {
            throw new IllegalArgumentException("JsonCreator must be a one-parameter delegating creator: " + type.getTypeName());
        }
        return parameterTypes[0];
    }

    private static JsonValueAccessor jsonValueAccessor(Class<?> type) {
        List<JsonValueAccessor> accessors = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            JsonValue annotation = method.getAnnotation(JsonValue.class);
            if (annotation != null && annotation.value()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    throw new IllegalArgumentException("JsonValue method must be a non-static zero-argument value: " + type.getTypeName());
                }
                accessors.add(JsonValueAccessor.forMethod(method));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            JsonValue annotation = field.getAnnotation(JsonValue.class);
            if (annotation != null && annotation.value()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalArgumentException("JsonValue field must be an instance value: " + type.getTypeName());
                }
                accessors.add(JsonValueAccessor.forField(field));
            }
        }
        if (accessors.size() > 1) {
            throw new IllegalArgumentException("Only one JsonValue accessor is supported for input type: " + type.getTypeName());
        }
        return accessors.isEmpty() ? null : accessors.getFirst();
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
        if ((!metadata.minimum().isEmpty() || !metadata.maximum().isEmpty()) && !"integer".equals(schema.get("type")) && !"number".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Bounds are only supported for numeric input properties");
        }
        if (!metadata.minimum().isEmpty()) {
            schema.put("minimum", parseDecimal(metadata.minimum(), "minimum"));
        }
        if (!metadata.maximum().isEmpty()) {
            schema.put("maximum", parseDecimal(metadata.maximum(), "maximum"));
        }
        if (schema.containsKey("minimum") && schema.containsKey("maximum") && ((BigDecimal) schema.get("maximum")).compareTo((BigDecimal) schema.get("minimum")) < 0) {
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
        return type == byte.class || type == Byte.class || type == short.class || type == Short.class || type == int.class || type == Integer.class || type == long.class || type == Long.class || type == BigInteger.class;
    }

    private static boolean isDecimal(Class<?> type) {
        return type == float.class || type == Float.class || type == double.class || type == Double.class || type == BigDecimal.class;
    }

    private static Map<String, Object> schemaWithType(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        return schema;
    }

    private record JsonValueAccessor(Type type, Method method, Field field) {
        static JsonValueAccessor forMethod(Method method) {
            return new JsonValueAccessor(method.getGenericReturnType(), method, null);
        }

        static JsonValueAccessor forField(Field field) {
            return new JsonValueAccessor(field.getGenericType(), null, field);
        }

        Object read(Object target) {
            try {
                if (method != null) {
                    if (!method.trySetAccessible()) {
                        throw new IllegalArgumentException("JsonValue method is inaccessible: " + method);
                    }
                    return method.invoke(target);
                }
                if (!field.trySetAccessible()) {
                    throw new IllegalArgumentException("JsonValue field is inaccessible: " + field);
                }
                return field.get(target);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalArgumentException("Cannot read JsonValue metadata", exception);
            }
        }
    }
}
