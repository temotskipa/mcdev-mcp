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

public final class DatabaseLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, DatabaseLockState> LOCKS = new ConcurrentHashMap<>();
    private static final Duration RETRY_DELAY = Duration.ofMillis(25);
    
    private final Lock localLock;
    private final DatabaseLockState state;
    private final boolean shared;
    private final FileChannel channel;
    private final FileLock fileLock;
    
    private DatabaseLock(Lock localLock, DatabaseLockState state, boolean shared, FileChannel channel, FileLock fileLock) {
        this.localLock = localLock;
        this.state = state;
        this.shared = shared;
        this.channel = channel;
        this.fileLock = fileLock;
    }
    
    public static DatabaseLock read(Path database, Duration timeout) throws IOException {
        return acquire(database, timeout, true);
    }
    
    public static DatabaseLock write(Path database, Duration timeout) throws IOException {
        return acquire(database, timeout, false);
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
        DatabaseLockState state = LOCKS.computeIfAbsent(lockPath, ignored -> new DatabaseLockState());
        Lock localLock = shared ? state.lock.readLock() : state.lock.writeLock();
        try {
            if (!localLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw timeoutFailure(shared, timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring " + mode(shared) + " database lock", exception);
        }
        try {
            if (shared) {
                acquireSharedLock(state, lockPath, timeout);
                return new DatabaseLock(localLock, state, true, null, null);
            }
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                FileLock fileLock = acquireFileLock(channel, false, timeout);
                return new DatabaseLock(localLock, state, false, channel, fileLock);
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
    
    private static void acquireSharedLock(DatabaseLockState state, Path lockPath, Duration timeout) throws IOException {
        synchronized (state.sharedGuard) {
            if (state.sharedReferences++ > 0) {
                return;
            }
            try {
                state.sharedChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                state.sharedFileLock = acquireFileLock(state.sharedChannel, true, timeout);
            } catch (IOException | RuntimeException exception) {
                state.sharedReferences = 0;
                if (state.sharedChannel != null) {
                    try {
                        state.sharedChannel.close();
                    } catch (IOException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                }
                state.sharedChannel = null;
                throw exception;
            }
        }
    }
    
    private static FileLock acquireFileLock(FileChannel channel, boolean shared, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                FileLock lock = channel.tryLock(0, Long.MAX_VALUE, shared);
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // A competing process may release its lock before this timeout expires.
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
    
    public boolean isHeld() {
        synchronized (state.sharedGuard) {
            return shared ? state.sharedFileLock != null && state.sharedFileLock.isValid() : fileLock.isValid();
        }
    }
    
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (shared) {
                releaseSharedLock();
            }
            else {
                fileLock.release();
                channel.close();
            }
        } catch (IOException exception) {
            failure = exception;
        } finally {
            localLock.unlock();
        }
        if (failure != null) {
            throw failure;
        }
    }
    
    private void releaseSharedLock() throws IOException {
        synchronized (state.sharedGuard) {
            if (--state.sharedReferences != 0) {
                return;
            }
            IOException failure = null;
            try {
                state.sharedFileLock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                state.sharedChannel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            } finally {
                state.sharedFileLock = null;
                state.sharedChannel = null;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
