package dev.mcdevmcp.analysis.index;

record OffsetRange(long start, long end) {
    boolean contains(long candidateStart, long candidateEnd) {
        long effectiveEnd = Math.max(candidateEnd, candidateStart);
        return candidateStart >= start && effectiveEnd <= end;
    }
}
