package dev.mcdevmcp.mcp.binding;

import java.net.URI;
import java.time.Duration;

record DomainArguments(URI uri, Duration timeout) {
}
