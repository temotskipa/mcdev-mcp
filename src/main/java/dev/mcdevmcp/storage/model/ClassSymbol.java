package dev.mcdevmcp.storage.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@SuppressWarnings("unused")
public record ClassSymbol(
        long id,
        String namespace,
        String binaryName,
        String packageName,
        String simpleName,
        TypeKind kind,
        Optional<String> superclassBinaryName,
        List<String> interfaceBinaryNames,
        Path sourcePath,
        int startOffset,
        int endOffset,
        int startLine,
        int endLine) {
    public ClassSymbol {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(kind, "kind");
        superclassBinaryName = Optional.ofNullable(superclassBinaryName)
                .orElseThrow(() -> new NullPointerException("superclassBinaryName"));
        interfaceBinaryNames = List.copyOf(interfaceBinaryNames);
        Objects.requireNonNull(sourcePath, "sourcePath");
    }
}
