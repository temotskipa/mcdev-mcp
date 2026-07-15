package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.transport.SdkJsonMode;

import java.time.Instant;
import java.util.Map;

record WireArguments(String uri, String path, long timeoutMs, Instant startedAt, SdkJsonMode mode, Map<String, Object> options) {
}
