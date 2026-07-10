package dev.mcdevmcp.indexer.protocol;

import com.google.gson.*;

import java.util.Set;

public final class ProtocolJson {
    private static final Gson GSON = new Gson();
    private static final Set<String> REQUEST_FIELDS = Set.of("id", "files");
    private static final Set<String> SOURCE_FILE_FIELDS = Set.of("path");
    
    private ProtocolJson() {
    }
    
    public static Request parseRequest(String input) {
        JsonObject root = parseObject(input, "request");
        rejectUnknownFields(root, REQUEST_FIELDS, "request");
        requireField(root, "id", "request");
        requireField(root, "files", "request");
        requireInteger(root.get("id"), "request.id");
        requireSourceFileArray(root.get("files"));
        
        Request request = GSON.fromJson(root, Request.class);
        request.validate();
        return request;
    }
    
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }
    
    private static JsonObject parseObject(String input, String name) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(input);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }
    
    private static void rejectUnknownFields(JsonObject object, Set<String> allowedFields, String name) {
        for (String field : object.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException(name + " contains unknown field '" + field + "'");
            }
        }
    }
    
    private static void requireField(JsonObject object, String field, String name) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException(name + " is missing required field '" + field + "'");
        }
    }
    
    private static void requireInteger(JsonElement element, String name) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }
    
    private static void requireSourceFileArray(JsonElement element) {
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("request.files must be an array");
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("request.files must contain only source file objects");
            }
            JsonObject sourceFile = item.getAsJsonObject();
            rejectUnknownFields(sourceFile, SOURCE_FILE_FIELDS, "source file");
            requireField(sourceFile, "path", "source file");
            JsonElement path = sourceFile.get("path");
            if (!path.isJsonPrimitive() || !path.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("source file path must be a string");
            }
        }
    }
}
