package dev.mcdevmcp.indexer.model;

import java.util.List;

public record FieldInfo(String name, String type, List<String> modifiers) {
}