package dev.mcdevmcp.support;

@FunctionalInterface
public interface Cancellation {
    boolean isCancelled();

    default void throwIfCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Operation cancelled");
        }
    }

    static Cancellation none() {
        return () -> false;
    }
}
