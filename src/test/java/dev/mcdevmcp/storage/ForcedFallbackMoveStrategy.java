package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.*;

final class ForcedFallbackMoveStrategy implements DatabaseMoveStrategy {
    private final int failingFallbackMove;
    private final Path companionBeforeFallback;
    private int fallbackMoves;
    
    ForcedFallbackMoveStrategy() {
        this(0, null);
    }
    
    ForcedFallbackMoveStrategy(int failingFallbackMove) {
        this(failingFallbackMove, null);
    }
    
    ForcedFallbackMoveStrategy(Path companionBeforeFallback) {
        this(0, companionBeforeFallback);
    }
    
    private ForcedFallbackMoveStrategy(int failingFallbackMove, Path companionBeforeFallback) {
        this.failingFallbackMove = failingFallbackMove;
        this.companionBeforeFallback = companionBeforeFallback;
    }
    
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
            if (companionBeforeFallback != null) {
                Files.writeString(companionBeforeFallback, "unsafe");
            }
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "forced by test");
        }
        fallbackMoves++;
        if (fallbackMoves == failingFallbackMove) {
            throw new IOException("forced fallback move failure " + fallbackMoves);
        }
        Files.move(source, target, options);
    }
}
