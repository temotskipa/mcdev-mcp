package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DatabaseLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private static final Duration RETRY_DELAY = Duration.ofMillis(25);

    private final Lock localLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private DatabaseLock(Lock localLock, FileChannel channel, FileLock fileLock) {
        this.localLock = localLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static DatabaseLock read(Path database, Duration timeout) throws IOException {
        return acquire(database, timeout, true);
    }

    public static DatabaseLock write(Path database, Duration timeout) throws IOException {
        return acquire(database, timeout, false);
    }

    public boolean isHeld() {
        return fileLock.isValid();
    }

    private static DatabaseLock acquire(Path database, Duration timeout, boolean shared) throws IOException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("lock timeout must not be negative");
        }
        Path normalizedDatabase = database.toAbsolutePath().normalize();
        Path lockPath = normalizedDatabase.resolveSibling(normalizedDatabase.getFileName() + ".lock");
        Files.createDirectories(lockPath.getParent());
        ReentrantReadWriteLock local = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantReadWriteLock(true));
        Lock localLock = shared ? local.readLock() : local.writeLock();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!localLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw timeoutFailure(shared, timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring " + mode(shared) + " database lock", exception);
        }
        try {
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                FileLock fileLock = acquireFileLock(channel, shared, deadline, timeout);
                return new DatabaseLock(localLock, channel, fileLock);
            } catch (IOException | RuntimeException exception) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            localLock.unlock();
            throw exception;
        }
    }

    private static FileLock acquireFileLock(FileChannel channel, boolean shared, long deadline, Duration timeout) throws IOException {
        while (true) {
            try {
                FileLock lock = channel.tryLock(0, Long.MAX_VALUE, shared);
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // The process-local lock prevents this in normal operation; retry keeps races deterministic.
            }
            if (System.nanoTime() >= deadline) {
                throw timeoutFailure(shared, timeout);
            }
            try {
                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while acquiring " + mode(shared) + " database lock", exception);
            }
        }
    }

    private static IOException timeoutFailure(boolean shared, Duration timeout) {
        return new IOException("Timed out acquiring " + mode(shared) + " database lock after " + format(timeout) + "; close active queries and retry.");
    }

    private static String mode(boolean shared) {
        return shared ? "shared" : "exclusive";
    }

    private static String format(Duration duration) {
        if (duration.toNanos() % TimeUnit.SECONDS.toNanos(1) == 0) {
            long seconds = duration.toSeconds();
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        return duration.toMillis() + " milliseconds";
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            fileLock.release();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } finally {
            localLock.unlock();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
