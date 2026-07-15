package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.transport.SdkJsonMode;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

record DomainArguments(URI uri, Path path, Duration timeout, Instant startedAt, SdkJsonMode mode, Map<String, Object> options) {
}
