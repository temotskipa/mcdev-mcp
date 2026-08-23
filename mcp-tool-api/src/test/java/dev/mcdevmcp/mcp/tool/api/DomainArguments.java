package dev.mcdevmcp.mcp.tool.api;

import java.net.URI;
import java.time.Duration;

record DomainArguments(URI uri, Duration timeout) {
}
