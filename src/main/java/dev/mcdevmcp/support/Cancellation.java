package dev.mcdevmcp.support;

@FunctionalInterface
public interface Cancellation {
    static Cancellation none() {
        return () -> false;
    }

    boolean isCancelled();

    @SuppressWarnings("unused")
    default void throwIfCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Operation cancelled");
        }
    }
}
