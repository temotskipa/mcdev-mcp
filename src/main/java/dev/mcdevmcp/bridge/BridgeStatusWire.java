package dev.mcdevmcp.bridge;

public record BridgeStatusWire(String version, String mappingStatus, Boolean obfuscated, Long refs, String gameDir, String logsDir, String latestLog, Boolean latestLogExists, String debugLog, Boolean debugLogExists, Boolean sessionControlEnabled) {
}
