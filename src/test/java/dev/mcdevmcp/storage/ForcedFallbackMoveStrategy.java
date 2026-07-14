package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.*;

final class ForcedFallbackMoveStrategy implements DatabaseMoveStrategy {
    enum FailureTiming {
        BEFORE_SIDE_EFFECT,
        AFTER_SIDE_EFFECT
    }

    private final int failingFallbackMove;
    private final FailureTiming failureTiming;
    private final Path companionBeforeFallback;
    private int fallbackMoves;
    
    ForcedFallbackMoveStrategy() {
        this(0, null, FailureTiming.BEFORE_SIDE_EFFECT);
    }
    
    ForcedFallbackMoveStrategy(int failingFallbackMove) {
        this(failingFallbackMove, null, FailureTiming.BEFORE_SIDE_EFFECT);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove, FailureTiming failureTiming) {
        this(failingFallbackMove, null, failureTiming);
    }
    
    ForcedFallbackMoveStrategy(Path companionBeforeFallback) {
        this(0, companionBeforeFallback, FailureTiming.BEFORE_SIDE_EFFECT);
    }
    
    private ForcedFallbackMoveStrategy(int failingFallbackMove, Path companionBeforeFallback, FailureTiming failureTiming) {
        this.failingFallbackMove = failingFallbackMove;
        this.companionBeforeFallback = companionBeforeFallback;
        this.failureTiming = failureTiming;
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
            if (failureTiming == FailureTiming.AFTER_SIDE_EFFECT) {
                Files.move(source, target, options);
            }
            throw new IOException("forced fallback move failure " + fallbackMoves);
        }
        Files.move(source, target, options);
    }
}
