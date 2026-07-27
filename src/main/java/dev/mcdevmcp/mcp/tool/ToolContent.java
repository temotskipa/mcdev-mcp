package dev.mcdevmcp.mcp.tool;

import java.util.Objects;

public record ToolContent(ToolContentType type, String text, String mimeType, String data) {
    public ToolContent {
        Objects.requireNonNull(type, "Content type");

        switch (type) {
            case TEXT -> {
                Objects.requireNonNull(text, "Text content");
                if (mimeType != null || data != null) {
                    throw new IllegalArgumentException("Text content cannot include MIME type or binary data");
                }
            }
            case IMAGE, AUDIO -> {
                Objects.requireNonNull(mimeType, "Binary content MIME type");
                Objects.requireNonNull(data, "Binary content data");
                if (text != null) {
                    throw new IllegalArgumentException("Binary content cannot include text");
                }
            }
        }
    }

    public static ToolContent text(String text) {
        return new ToolContent(ToolContentType.TEXT, text, null, null);
    }

    public static ToolContent image(String data, String mimeType) {
        return new ToolContent(ToolContentType.IMAGE, null, mimeType, data);
    }
}
