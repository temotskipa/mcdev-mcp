package dev.mcdevmcp.support;

import com.google.gson.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonSupport {
    private static final Gson GSON = new Gson();
    
    private JsonSupport() {
    }
    
    public static JsonArray readArrayResource(String resource) {
        try (InputStream input = resource(resource)) {
            var parsed = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) {
                throw new IllegalArgumentException("Expected JSON array resource: " + resource);
            }
            return parsed.getAsJsonArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read resource: " + resource, exception);
        }
    }
    
    public static String readTextResource(String resource) {
        try (InputStream input = resource(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read resource: " + resource, exception);
        }
    }
    
    public static JsonObject toJsonObject(Map<String, Object> value) {
        return GSON.toJsonTree(value == null ? Map.of() : value).getAsJsonObject();
    }
    
    public static Map<String, Object> toMap(JsonObject value) {
        var map = new LinkedHashMap<String, Object>();
        value.entrySet().forEach(entry -> map.put(entry.getKey(), toJavaValue(entry.getValue())));
        return map;
    }
    
    private static InputStream resource(String resource) {
        InputStream input = JsonSupport.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("Missing classpath resource: " + resource);
        }
        return input;
    }
    
    private static Object toJavaValue(JsonElement value) {
        if (value.isJsonObject()) {
            return toMap(value.getAsJsonObject());
        }
        if (value.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                values.add(toJavaValue(item));
            }
            return values;
        }
        if (value.isJsonNull()) {
            return null;
        }
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        String number = primitive.getAsString();
        if (!number.contains(".") && !number.contains("e") && !number.contains("E")) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException ignored) {
                // Keep values outside the long range lossless.
            }
        }
        return new BigDecimal(number);
    }
}
