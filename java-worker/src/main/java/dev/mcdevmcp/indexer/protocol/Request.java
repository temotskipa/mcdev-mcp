package dev.mcdevmcp.indexer.protocol;

import java.util.List;

public record Request(Integer id, List<SourceFile> files) {
    public void validate() {
        if (id == null) {
            throw new IllegalArgumentException("missing id");
        }
        if (files == null) {
            throw new IllegalArgumentException("missing files");
        }
        for (SourceFile file : files) {
            if (file == null) {
                throw new IllegalArgumentException("files must contain only source file objects");
            }
        }
    }
}