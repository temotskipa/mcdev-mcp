package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

final class RuntimeToolSupport {
    static final Map<String, Object> EMPTY_PAYLOAD = Map.of();

    private static final int DEFAULT_PORT = 9876;
    private static final int PORTS_TO_SCAN = 11;

    private final BridgeSession session;
    private final McpJsonMapper mapper;

    RuntimeToolSupport(BridgeSession session, McpJsonMapper mapper) {
        this.session = Objects.requireNonNull(session, "session");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    static Map<String, Object> payload(Object... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("Runtime payload fields must be name-value pairs");
        }
        var payload = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            String name = (String) fields[index];
            Object value = fields[index + 1];
            if (value != null) {
                payload.put(name, value);
            }
        }
        return Collections.unmodifiableMap(payload);
    }

    private static Number optionalNumber(Object value, String name) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && isFinite(number)) {
            return number;
        }
        throw new IllegalArgumentException("'" + name + "' must be a finite number");
    }

    private static Number requiredNumber(Object value, String name) {
        Number number = optionalNumber(value, name);
        if (number == null) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return number;
    }

    static BigDecimal optionalDecimal(Object value, String name) {
        Number number = optionalNumber(value, name);
        return number == null ? null : toBigDecimal(number);
    }

    static BigDecimal requiredDecimal(Object value, String name) {
        return toBigDecimal(requiredNumber(value, name));
    }

    private static BigDecimal toBigDecimal(Number number) {
        BigDecimal decimal = switch (number) {
            case BigDecimal value -> value;
            case BigInteger value -> new BigDecimal(value);
            case Byte _, Short _, Integer _, Long _ -> BigDecimal.valueOf(number.longValue());
            default -> BigDecimal.valueOf(number.doubleValue());
        };
        BigDecimal normalized = decimal.stripTrailingZeros();
        return normalized.scale() < 0 ? new BigDecimal(normalized.toBigIntegerExact()) : normalized;
    }

    static double requiredTimeoutNumber(Object value) {
        return requiredNumber(value, "timeoutMs").doubleValue();
    }

    static String requiredString(Object value, String name) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("'" + name + "' is required and must be a string");
    }

    static boolean requiredGlow(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException("'glow' is required and must be a boolean");
    }

    static Boolean optionalBoolean(Object value, String name) {
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new IllegalArgumentException("'" + name + "' must be a boolean");
    }

    static String requiredCode(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("'code' is required and must be a string");
    }

    static Integer optionalPort(Object value) {
        Number number = optionalNumber(value, "port");
        if (number == null) {
            return null;
        }
        double numeric = number.doubleValue();
        if (numeric != Math.rint(numeric) || numeric < 1 || numeric > 65535) {
            throw new IllegalArgumentException("'port' must be an integer from 1 to 65535");
        }
        return (int) numeric;
    }

    static int timeoutMillis(Object value) {
        if (value == null) {
            return 10_000;
        }
        double numeric = requiredTimeoutNumber(value);
        if (numeric != Math.rint(numeric) || numeric < 1_000 || numeric > 300_000) {
            throw new IllegalArgumentException("'timeoutMs' must be an integer from 1000 to 300000");
        }
        return (int) numeric;
    }

    static ToolResult declaredFailure(BridgeResponse response) {
        String error = response.error() == null ? "undefined" : response.error();
        return response.success() ? null : ToolResult.error("Error: " + error);
    }

    static Object requireResult(BridgeEndpoint endpoint, BridgeResponse response) {
        if (!response.resultPresent() || response.result() == null) {
            throw missingResult(endpoint);
        }
        return response.result();
    }

    private static IllegalArgumentException missingResult(BridgeEndpoint endpoint) {
        return new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' reported success but returned no result. This is a bridge protocol mismatch — please verify the DebugBridge mod version.");
    }

    private static String formatSessionInfo(SessionInfo info, int port) {
        var lines = new ArrayList<String>();
        lines.add("Minecraft " + info.version().value());
        lines.add("Port: " + port);
        info.gameDir().map(Path::toString).ifPresent(path -> lines.add("Game dir: " + path));
        info.latestLog().map(Path::toString).ifPresent(path -> lines.add("Log: " + path));
        lines.add("Mappings: " + info.mappingStatus().name().toLowerCase(Locale.ROOT));
        info.sessionControlEnabled().ifPresent(enabled -> lines.add("Session control: " + (enabled ? "enabled" : "disabled")));
        return String.join("\n", lines);
    }

    static String nodeNumber(Number number) {
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("DebugBridge JSON number must be finite");
        }
        if (value == 0) {
            return "0";
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        int exponent = decimal.precision() - decimal.scale() - 1;
        if (exponent > -7 && exponent < 21) {
            return decimal.toPlainString();
        }
        String digits = decimal.unscaledValue().abs().toString();
        String fraction = digits.length() == 1 ? "" : "." + digits.substring(1);
        String exponentSign = exponent >= 0 ? "+" : "";
        return (decimal.signum() < 0 ? "-" : "") + digits.charAt(0) + fraction + "e" + exponentSign + exponent;
    }

    private static boolean jsonTruthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean flag -> flag;
            case Number number -> number.doubleValue() != 0;
            case String text -> !text.isEmpty();
            default -> true;
        };
    }

    static boolean isFinite(Number number) {
        return Double.isFinite(number.doubleValue());
    }

    static String describe(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            case String ignored -> "string";
            case Boolean ignored -> "boolean";
            case Number ignored -> "number";
            default -> value.getClass().getSimpleName();
        };
    }

    private static String message(Throwable failure) {
        Throwable unwrapped = failure;
        while ((unwrapped instanceof java.util.concurrent.CompletionException || unwrapped instanceof java.util.concurrent.ExecutionException) && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        return unwrapped.getMessage() == null ? unwrapped.toString() : unwrapped.getMessage();
    }

    private static void indent(StringBuilder target, int depth) {
        target.repeat("  ", depth);
    }

    CompletionStage<ToolResult> connect(ConnectArguments arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.reset()) {
            session.reset();
        }
        if (!arguments.reset() && arguments.port() == null && session.connectedPort().isPresent()) {
            SessionInfo info = session.sessionInfo().orElseThrow(() -> new IllegalStateException("DebugBridge connected without session information"));
            return CompletableFuture.completedFuture(ToolResult.text("Already connected.\n" + formatSessionInfo(info, session.connectedPort().orElse(info.port())) + "\n\nUse reset=true to reconnect."));
        }
        return mapStage(() -> session.connect(arguments.port()), info -> ToolResult.text("Connected!\n" + formatSessionInfo(info, session.connectedPort().orElse(info.port()))), failure -> connectFailure(arguments.port(), failure));
    }

    CompletionStage<ToolResult> execute(ExecuteArguments arguments, ScriptLogger scriptLogger, boolean scriptLogsEnabled) {
        long started = System.currentTimeMillis();
        Map<String, Object> payload = payload("code", arguments.code(), "timeoutMs", arguments.timeoutMillis());
        Duration timeout = Duration.ofMillis(arguments.timeoutMillis());
        CompletionStage<BridgeResponse> sent;
        try {
            sent = session.send(McExecuteTool.ENDPOINT, payload, timeout);
        } catch (RuntimeException exception) {
            if (scriptLogsEnabled) {
                scriptLogger.log(ScriptLogger.ScriptLogEntry.failed(arguments.code(), message(exception), Duration.ofMillis(System.currentTimeMillis() - started)), false);
            }
            return CompletableFuture.completedFuture(ToolResult.error(message(exception)));
        }
        return sent.handle((response, exception) -> {
            long duration = System.currentTimeMillis() - started;
            if (exception != null) {
                String failure = message(exception);
                if (scriptLogsEnabled) {
                    scriptLogger.log(ScriptLogger.ScriptLogEntry.failed(arguments.code(), failure, Duration.ofMillis(duration)), false);
                }
                return ToolResult.error(failure);
            }
            if (scriptLogsEnabled) {
                scriptLogger.log(ScriptLogger.ScriptLogEntry.completed(response.success(), arguments.code(), response.resultPresent(), response.result(), response.output(), response.error(), Duration.ofMillis(duration)), true);
            }
            try {
                ToolResult failure = declaredFailure(response);
                if (failure != null) {
                    return failure;
                }
                StringBuilder text = new StringBuilder();
                if (response.output() != null && !response.output().isEmpty()) {
                    text.append(response.output()).append('\n');
                }
                if (response.resultPresent() && jsonTruthy(response.result())) {
                    text.append(prettyJson(response.result()));
                }
                String rendered = text.toString().strip();
                return ToolResult.text(rendered.isEmpty() ? "(no output)" : rendered);
            } catch (RuntimeException renderingFailure) {
                return ToolResult.error(message(renderingFailure));
            }
        });
    }

    CompletionStage<ToolResult> container(BridgeEndpoint endpoint, Map<String, Object> payload) {
        return request(endpoint, payload, null, response -> {
            ToolResult failure = declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            Object result = requireResult(endpoint, response);
            if (!(result instanceof Map<?, ?>) && !(result instanceof List<?>)) {
                throw new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' returned non-container result (got " + describe(result) + "). Expected a JSON object or array.");
            }
            return ToolResult.text(prettyJson(result));
        });
    }

    CompletionStage<ToolResult> lookedAtEntity(LookedAtEntityArguments arguments) {
        Map<String, Object> payload = payload("range", arguments.range());
        return request(McLookedAtEntityTool.ENDPOINT, payload, null, response -> {
            ToolResult failure = declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            if (!response.resultPresent()) {
                throw missingResult(McLookedAtEntityTool.ENDPOINT);
            }
            Object result = response.result();
            if (result == null) {
                return ToolResult.text("null");
            }
            if (!(result instanceof Number number) || !isFinite(number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException("Bridge 'lookedAtEntity' returned malformed result: expected an integer entity id or null, got " + describe(result) + ".");
            }
            return ToolResult.text(prettyJson(number));
        });
    }

    CompletionStage<ToolResult> request(BridgeEndpoint endpoint, Map<String, Object> payload, Duration timeout, Function<BridgeResponse, ToolResult> renderer) {
        return mapStage(() -> session.send(endpoint, payload, timeout), renderer, ToolResult::error);
    }

    private <T> CompletionStage<ToolResult> mapStage(Supplier<CompletionStage<T>> operation, Function<T, ToolResult> success, Function<String, ToolResult> failure) {
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(operation.get(), "Runtime operation returned no completion stage");
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure.apply(message(exception)));
        }
        return stage.handle((value, exception) -> {
            if (exception != null) {
                return failure.apply(message(exception));
            }
            try {
                return success.apply(value);
            } catch (RuntimeException renderingFailure) {
                return ToolResult.error(message(renderingFailure));
            }
        });
    }

    private ToolResult connectFailure(Integer explicitPort, String failure) {
        String lower = failure.toLowerCase(Locale.ROOT);
        boolean refused = lower.contains("econnrefused") || lower.contains("could not connect") || lower.contains("timed out connecting") || lower.contains("no debugbridge instance accepted status");
        List<Integer> ports = explicitPort == null ? IntStream.range(0, PORTS_TO_SCAN).map(index -> DEFAULT_PORT + index).boxed().toList() : List.of(explicitPort);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("connected", false);
        structured.put("action", refused ? "start_minecraft" : "investigate");
        structured.put("ports_tried", ports);
        structured.put("message", refused ? "DebugBridge mod is not running on any scanned port. Ask the user to launch Minecraft with the DebugBridge mod loaded, then retry mc_connect." : "Connection failed: " + failure);
        structured.put("raw_error", failure);
        return ToolResult.error(prettyJson(structured));
    }

    String prettyJson(Object value) {
        var result = new StringBuilder();
        appendJson(result, value, 0);
        return result.toString();
    }

    private void appendJson(StringBuilder target, Object value, int depth) {
        switch (value) {
            case null -> target.append("null");
            case String text -> target.append(quoted(text));
            case Boolean flag -> target.append(flag);
            case Number number -> target.append(nodeNumber(number));
            case Map<?, ?> object -> appendObject(target, object, depth);
            case List<?> array -> appendArray(target, array, depth);
            default ->
                    throw new IllegalArgumentException("Unsupported DebugBridge JSON value: " + value.getClass().getName());
        }
    }

    private void appendObject(StringBuilder target, Map<?, ?> object, int depth) {
        if (object.isEmpty()) {
            target.append("{}");
            return;
        }
        target.append("{\n");
        int index = 0;
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("DebugBridge JSON object key must be a string");
            }
            indent(target, depth + 1);
            target.append(quoted(key)).append(": ");
            appendJson(target, entry.getValue(), depth + 1);
            target.append(++index < object.size() ? ",\n" : "\n");
        }
        indent(target, depth);
        target.append('}');
    }

    private void appendArray(StringBuilder target, List<?> array, int depth) {
        if (array.isEmpty()) {
            target.append("[]");
            return;
        }
        target.append("[\n");
        for (int index = 0; index < array.size(); index++) {
            indent(target, depth + 1);
            appendJson(target, array.get(index), depth + 1);
            target.append(index + 1 < array.size() ? ",\n" : "\n");
        }
        indent(target, depth);
        target.append(']');
    }

    private String quoted(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to render DebugBridge JSON text", exception);
        }
    }
}
