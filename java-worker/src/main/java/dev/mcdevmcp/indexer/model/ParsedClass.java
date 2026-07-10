package dev.mcdevmcp.indexer.model;

public record ParsedClass(String packageName, String className, String fullName, ClassInfo info) {
}