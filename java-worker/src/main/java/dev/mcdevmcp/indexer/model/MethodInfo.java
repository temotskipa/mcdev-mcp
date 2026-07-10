package dev.mcdevmcp.indexer.model;

import java.util.List;

public record MethodInfo(String name, String returnType, List<ParamInfo> params, List<String> modifiers, long lineStart,
                         long lineEnd) {
}