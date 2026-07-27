package dev.mcdevmcp.tools.runtime;

import java.util.List;

record RecordVideoFramesResult(List<String> paths, int frameWidth, int frameHeight, String mimeType, int frameCount, long captureMillis, double intervalMillis, long sizeBytes, int dropped) {
}
