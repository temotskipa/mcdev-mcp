package dev.mcdevmcp.mcp;

import java.net.URI;

public record ResourceRead(URI uri, String mimeType, String text) {
}