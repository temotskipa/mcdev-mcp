package dev.mcdevmcp.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@FunctionalInterface
public interface ArgumentDecoder<A> {
    static <A> ArgumentDecoder<A> sdk(Class<A> type) {
        Objects.requireNonNull(type, "type");
        return (mapper, arguments) -> Objects.requireNonNull(mapper, "mapper").convertValue(arguments, type);
    }

    A decode(McpJsonMapper mapper, Map<String, Object> arguments);

    default <B> ArgumentDecoder<B> map(Function<A, B> converter) {
        Objects.requireNonNull(converter, "converter");
        return (mapper, arguments) -> converter.apply(decode(mapper, arguments));
    }
}
