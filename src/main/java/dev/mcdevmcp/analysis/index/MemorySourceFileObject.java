package dev.mcdevmcp.analysis.index;

import javax.tools.SimpleJavaFileObject;

final class MemorySourceFileObject extends SimpleJavaFileObject {
    private final DecodedSource source;

    MemorySourceFileObject(DecodedSource source) {
        super(source.uri(), Kind.SOURCE);
        this.source = source;
    }

    DecodedSource source() {
        return source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source.content();
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        return kind == Kind.SOURCE && source.relativePath().getFileName().toString().equals(simpleName + kind.extension);
    }
}
