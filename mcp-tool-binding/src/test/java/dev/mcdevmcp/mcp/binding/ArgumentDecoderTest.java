package dev.mcdevmcp.mcp.binding;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgumentDecoderTest {
    @Test
    void convertsTheCompleteArgumentMapAndThenMapsToDomainTypes() {
        var mapper = McpJsonDefaults.getMapper();
        var decoder = ArgumentDecoder.sdk(WireArguments.class).map(arguments -> new DomainArguments(URI.create(arguments.uri()), Duration.ofMillis(arguments.timeoutMs())));
        
        var result = decoder.decode(mapper, Map.of("uri", "https://example.test/tool", "timeoutMs", 1250L));
        
        assertEquals(new DomainArguments(URI.create("https://example.test/tool"), Duration.ofMillis(1250)), result);
    }
}
