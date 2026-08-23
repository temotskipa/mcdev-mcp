package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.mcp.tool.api.ToolContent;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

final class MediaToolSupport {
    static final int MAX_BASE64_PNG_BYTES = 7 * 1024 * 1024;
    private static final Pattern JAVASCRIPT_DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private final RuntimeToolSupport runtime;

    MediaToolSupport(RuntimeToolSupport runtime) {
        this.runtime = runtime;
    }

    static RecordInterval normalizeRecordInterval(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            if (!RuntimeToolSupport.isFinite(number)) {
                throw new IllegalArgumentException("'interval' must be a finite number or string");
            }
            return new RecordInterval.Milliseconds(RuntimeToolSupport.requiredDecimal(number, "interval"));
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("'interval' must be a finite number or string");
        }
        if (text.equals("frame") || text.isBlank()) {
            return new RecordInterval.Text(text);
        }
        BigDecimal numeric = javascriptNumber(text);
        if (numeric != null) {
            return new RecordInterval.Milliseconds(numeric);
        }
        return new RecordInterval.Text(text);
    }

    private static BigDecimal javascriptNumber(String text) {
        String value = text.strip();
        try {
            if (value.length() > 2 && value.charAt(0) == '0') {
                int radix = switch (value.charAt(1)) {
                    case 'x', 'X' -> 16;
                    case 'b', 'B' -> 2;
                    case 'o', 'O' -> 8;
                    default -> 0;
                };
                if (radix != 0) {
                    double numeric = new BigInteger(value.substring(2), radix).doubleValue();
                    return Double.isFinite(numeric) ? RuntimeToolSupport.requiredDecimal(numeric, "interval") : null;
                }
            }
            if (!JAVASCRIPT_DECIMAL.matcher(value).matches()) {
                return null;
            }
            double numeric = Double.parseDouble(value);
            return Double.isFinite(numeric) ? RuntimeToolSupport.requiredDecimal(numeric, "interval") : null;
        } catch (NumberFormatException ignored) {
            // The bridge returns its own self-describing validation error.
            return null;
        }
    }

    static Duration recordingDeadline(BigDecimal frames, RecordInterval interval) {
        double perFrameMillis = interval == null ? 17 : interval.estimatedMillis();
        double captureMillis = frames.doubleValue() * perFrameMillis;
        if (!Double.isFinite(captureMillis) || captureMillis >= Long.MAX_VALUE - 15_000d) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
        long roundedCapture = Math.round(captureMillis);
        try {
            return Duration.ofMillis(Math.addExact(roundedCapture, 15_000L));
        } catch (ArithmeticException ignored) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
    }

    CompletionStage<ToolResult> screenshot(ScreenshotArguments arguments) {
        Map<String, Object> payload = RuntimeToolSupport.payload("downscale", arguments.downscale(), "quality", arguments.quality());
        return runtime.request(McScreenshotTool.ENDPOINT, payload, null, response -> {
            ToolResult failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            ScreenshotResult result = screenshotResult(response);
            String text = result.path() + "\n(" + number(result.width()) + "x" + number(result.height()) + " JPEG, " + fixedOne((double) result.sizeBytes() / 1024) + " KB)";
            return ToolResult.text(text);
        });
    }

    CompletionStage<ToolResult> recordVideo(RecordVideoArguments arguments) {
        Object interval = arguments.interval() == null ? null : arguments.interval().bridgeValue();
        Map<String, Object> payload = RuntimeToolSupport.payload("frames", arguments.frames(), "interval", interval, "output", arguments.output(), "gridCols", arguments.gridCols(), "downscale", arguments.downscale(), "quality", arguments.quality());
        return runtime.request(McRecordVideoTool.ENDPOINT, payload, recordingDeadline(arguments.frames(), arguments.interval()), response -> {
            ToolResult failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            Map<String, Object> object = requireObject(McRecordVideoTool.ENDPOINT, response);
            Object mode = object.get("mode");
            if (mode instanceof String text && text.equals("grid")) {
                return ToolResult.text(renderGrid(gridResult(response)));
            }
            if (mode instanceof String text && text.equals("frames")) {
                return ToolResult.text(renderFrames(framesResult(response, object)));
            }
            String renderedMode = mode instanceof String text ? "'" + text + "'" : javascriptType(object.containsKey("mode"), mode);
            throw new IllegalArgumentException("Bridge 'record_video' returned unknown mode: " + renderedMode + ".");
        });
    }

    CompletionStage<ToolResult> itemTexture(ItemTextureArguments arguments) {
        return texture(McGetItemTextureTool.ENDPOINT, RuntimeToolSupport.payload("slot", arguments.slot()));
    }

    CompletionStage<ToolResult> entityItemTexture(EntityItemTextureArguments arguments) {
        return texture(McGetEntityItemTextureTool.ENDPOINT, RuntimeToolSupport.payload("entityId", arguments.entityId(), "slot", arguments.slot()));
    }

    CompletionStage<ToolResult> itemTextureById(ItemTextureByIdArguments arguments) {
        return texture(McGetItemTextureByIdTool.ENDPOINT, RuntimeToolSupport.payload("itemId", arguments.itemId()));
    }

    CompletionStage<ToolResult> acknowledgement(BridgeEndpoint endpoint, Map<String, Object> payload) {
        return runtime.request(endpoint, payload, null, response -> {
            ToolResult failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            return ToolResult.text(runtime.prettyJson(RuntimeToolSupport.requireResult(endpoint, response)));
        });
    }

    private CompletionStage<ToolResult> texture(BridgeEndpoint endpoint, Map<String, Object> payload) {
        return runtime.request(endpoint, payload, null, response -> {
            ToolResult failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            TextureResult result = textureResult(endpoint, response);
            checkBase64Bound(result.base64Png(), endpoint);
            return ToolResult.content(List.of(ToolContent.image(result.base64Png(), "image/png"), ToolContent.text(number(result.width()) + "x" + number(result.height()) + " sprite=" + result.spriteName())), false);
        });
    }

    private static ScreenshotResult screenshotResult(BridgeResponse response) {
        Map<String, Object> object = expectShape(McScreenshotTool.ENDPOINT, response, List.of("path", "mimeType"), List.of("width", "height", "sizeBytes"));
        return new ScreenshotResult((String) object.get("path"), intField(object, "width"), intField(object, "height"), longField(object, "sizeBytes"), (String) object.get("mimeType"));
    }

    private static TextureResult textureResult(BridgeEndpoint endpoint, BridgeResponse response) {
        Map<String, Object> object = expectShape(endpoint, response, List.of("base64Png", "spriteName"), List.of("width", "height"));
        return new TextureResult((String) object.get("base64Png"), intField(object, "width"), intField(object, "height"), (String) object.get("spriteName"));
    }

    private static RecordVideoGridResult gridResult(BridgeResponse response) {
        Map<String, Object> object = expectShape(McRecordVideoTool.ENDPOINT, response, List.of("mode", "path", "mimeType"), List.of("width", "height", "sizeBytes", "frameCount", "frameWidth", "frameHeight", "gridCols", "gridRows", "captureMs", "intervalMs", "dropped"));
        return new RecordVideoGridResult((String) object.get("path"), intField(object, "width"), intField(object, "height"), longField(object, "sizeBytes"), (String) object.get("mimeType"), intField(object, "frameCount"), intField(object, "frameWidth"), intField(object, "frameHeight"), intField(object, "gridCols"), intField(object, "gridRows"), longField(object, "captureMs"), intervalMillis(object), intField(object, "dropped"));
    }

    private static RecordVideoFramesResult framesResult(BridgeResponse response, Map<String, Object> object) {
        Object pathsValue = object.get("paths");
        if (!(pathsValue instanceof List<?> paths) || !paths.stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException("Bridge 'record_video' returned malformed result: 'paths' should be a string array.");
        }
        Map<String, Object> shaped = expectShape(McRecordVideoTool.ENDPOINT, response, List.of("mode", "mimeType"), List.of("frameWidth", "frameHeight", "frameCount", "captureMs", "intervalMs", "sizeBytes", "dropped"));
        return new RecordVideoFramesResult(paths.stream().map(String.class::cast).toList(), intField(shaped, "frameWidth"), intField(shaped, "frameHeight"), (String) shaped.get("mimeType"), intField(shaped, "frameCount"), longField(shaped, "captureMs"), intervalMillis(shaped), longField(shaped, "sizeBytes"), intField(shaped, "dropped"));
    }

    private static String renderGrid(RecordVideoGridResult result) {
        return result.path() + "\n(" + number(result.width()) + "x" + number(result.height()) + " JPEG, " + fixedOne((double) result.sizeBytes() / 1024) + " KB; " + number(result.frameCount()) + " frames @ " + number(result.frameWidth()) + "x" + number(result.frameHeight()) + ", grid " + number(result.gridCols()) + "x" + number(result.gridRows()) + "; capture " + number(result.captureMillis()) + "ms, avg " + fixedOne(result.intervalMillis()) + "ms" + dropNote(result.dropped()) + ")";
    }

    private static String renderFrames(RecordVideoFramesResult result) {
        return number(result.frameCount()) + " frames @ " + number(result.frameWidth()) + "x" + number(result.frameHeight()) + " JPEG, " + fixedOne((double) result.sizeBytes() / 1024) + " KB total; capture " + number(result.captureMillis()) + "ms, avg " + fixedOne(result.intervalMillis()) + "ms" + dropNote(result.dropped()) + "\n" + String.join("\n", result.paths());
    }

    private static String dropNote(int dropped) {
        return dropped > 0 ? ", " + number(dropped) + " dropped" : "";
    }

    private static int intField(Map<String, Object> object, String field) {
        return ((Number) object.get(field)).intValue();
    }

    private static long longField(Map<String, Object> object, String field) {
        return ((Number) object.get(field)).longValue();
    }

    private static double intervalMillis(Map<String, Object> object) {
        return ((Number) object.get("intervalMs")).doubleValue();
    }

    private static Map<String, Object> expectShape(BridgeEndpoint endpoint, BridgeResponse response, List<String> stringFields, List<String> numberFields) {
        Map<String, Object> object = requireObject(endpoint, response);
        var errors = new ArrayList<String>();
        for (String field : stringFields) {
            Object value = object.get(field);
            if (!(value instanceof String)) {
                errors.add("'" + field + "' should be string, got " + RuntimeToolSupport.describe(value));
            }
        }
        for (String field : numberFields) {
            Object value = object.get(field);
            if (!(value instanceof Number number) || !RuntimeToolSupport.isFinite(number)) {
                errors.add("'" + field + "' should be a finite number, got " + RuntimeToolSupport.describe(value));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' returned malformed result: " + String.join("; ", errors) + ".");
        }
        return object;
    }

    private static Map<String, Object> requireObject(BridgeEndpoint endpoint, BridgeResponse response) {
        Object result = RuntimeToolSupport.requireResult(endpoint, response);
        if (!(result instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' returned non-object result (got " + RuntimeToolSupport.describe(result) + "). Expected a JSON object.");
        }
        var copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' returned an object with a non-string key.");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static void checkBase64Bound(String base64, BridgeEndpoint endpoint) {
        if (base64.length() <= MAX_BASE64_PNG_BYTES) {
            return;
        }
        String size = fixedOne((double) base64.length() / 1024 / 1024);
        String maximum = fixedOne((double) MAX_BASE64_PNG_BYTES / 1024 / 1024);
        throw new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' returned a " + size + " MB base64 PNG, exceeding the " + maximum + " MB cap. This usually means a malformed bridge response — please report it.");
    }

    private static String number(Number value) {
        return RuntimeToolSupport.nodeNumber(value);
    }

    private static String fixedOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String javascriptType(boolean present, Object value) {
        if (!present) {
            return "undefined";
        }
        return switch (value) {
            case null -> "object";
            case Map<?, ?> _, List<?> _ -> "object";
            case Number _ -> "number";
            case Boolean _ -> "boolean";
            default -> value.getClass().getSimpleName();
        };
    }
}
