package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@FunctionalInterface
public interface ArgumentDecoder<A> {
    static <A> ArgumentDecoder<A> sdk(Class<A> type) {
        return sdk(JsonType.of(Objects.requireNonNull(type, "type")));
    }

    static <A> ArgumentDecoder<A> sdk(JsonType<A> type) {
        JsonType<A> required = Objects.requireNonNull(type, "type");
        return required::decode;
    }

    A decode(McpJsonMapper mapper, Map<String, Object> arguments);

    default <B> ArgumentDecoder<B> map(Function<A, B> converter) {
        Objects.requireNonNull(converter, "converter");
        return (mapper, arguments) -> converter.apply(decode(mapper, arguments));
    }
}
