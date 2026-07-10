package dev.mcdevmcp.indexer.protocol;

public record Failure(SourceFile file, String error) {
}