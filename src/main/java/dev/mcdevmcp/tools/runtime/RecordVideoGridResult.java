package dev.mcdevmcp.tools.runtime;

record RecordVideoGridResult(String path, int width, int height, long sizeBytes, String mimeType, int frameCount, int frameWidth, int frameHeight, int gridCols, int gridRows, long captureMillis, double intervalMillis, int dropped) {
}
