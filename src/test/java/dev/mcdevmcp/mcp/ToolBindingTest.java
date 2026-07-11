package dev.mcdevmcp.mcp;

import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.JsonValues;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolBindingTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void decodesTheWholeArgumentMapOnceAndConvertsWireValuesToDomainValues() {
        var options = new LinkedHashMap<String, Object>();
        options.put("enabled", true);
        options.put("missing", null);
        options.put("values", new ArrayList<>(List.of("one", 2L)));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("uri", "https://example.test/tool");
        arguments.put("path", "build/output.txt");
        arguments.put("timeoutMs", 1250L);
        arguments.put("startedAt", "2026-07-10T12:34:56Z");
        arguments.put("mode", "SAFE");
        arguments.put("options", options);
        var mapper = new CountingMcpJsonMapper(MAPPER);
        var received = new CompletableFuture<DomainArguments>();
        var binding = new ToolBinding<>(
                ArgumentDecoder.<WireArguments>sdk(WireArguments.class).<DomainArguments>map(wire -> new DomainArguments(
                        URI.create(wire.uri()),
                        Path.of(wire.path()),
                        Duration.ofMillis(wire.timeoutMs()),
                        wire.startedAt(),
                        wire.mode(),
                        JsonValues.freezeMap(wire.options()))),
                (domain, _) -> {
                    received.complete(domain);
                    return ToolHandlers.completed(ToolResult.text("ok"));
                });

        var result = binding.invoke(mapper, arguments, Cancellation.none()).toCompletableFuture().resultNow();
        var domain = received.resultNow();

        assertFalse(result.isError());
        assertEquals(1, mapper.convertValueCalls());
        assertEquals(URI.create("https://example.test/tool"), domain.uri());
        assertEquals(Path.of("build/output.txt"), domain.path());
        assertEquals(Duration.ofMillis(1250), domain.timeout());
        assertEquals(Instant.parse("2026-07-10T12:34:56Z"), domain.startedAt());
        assertEquals(SdkJsonMode.SAFE, domain.mode());
        assertNull(domain.options().get("missing"));
        assertThrows(UnsupportedOperationException.class, () -> domain.options().put("later", false));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) domain.options().get("values")).clear());
    }

    @Test
    void propagatesSynchronousDecoderFailureBeforeCallingTheHandler() {
        var handlerCalled = new CompletableFuture<Void>();
        var binding = new ToolBinding<TestEmptyArguments>(
                (_, _) -> {
                    throw new IllegalArgumentException("bad arguments");
                },
                (_, _) -> {
                    handlerCalled.complete(null);
                    return ToolHandlers.completed(ToolResult.text("unexpected"));
                });

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()));

        assertEquals("bad arguments", exception.getMessage());
        assertFalse(handlerCalled.isDone());
    }

    @Test
    void preservesAsynchronousHandlerFailure() {
        var binding = new ToolBinding<>(
                ArgumentDecoder.sdk(TestEmptyArguments.class),
                (_, _) -> CompletableFuture.failedFuture(new IllegalStateException("async failure")));

        var exception = assertThrows(
                CompletionException.class,
                () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()).toCompletableFuture().join());

        assertEquals("async failure", exception.getCause().getMessage());
    }
}

record WireArguments(String uri, String path, long timeoutMs, Instant startedAt, SdkJsonMode mode, Map<String, Object> options) {
}

record DomainArguments(URI uri, Path path, Duration timeout, Instant startedAt, SdkJsonMode mode, Map<String, Object> options) {
}

final class CountingMcpJsonMapper implements McpJsonMapper {
    private final McpJsonMapper delegate;
    private int convertValueCalls;

    CountingMcpJsonMapper(McpJsonMapper delegate) {
        this.delegate = delegate;
    }

    int convertValueCalls() {
        return convertValueCalls;
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        convertValueCalls++;
        return delegate.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        convertValueCalls++;
        return delegate.convertValue(value, type);
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(value);
    }
}
