package dev.mcdevmcp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseLockTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exclusiveCloseClosesTheChannelWhenFileLockReleaseFails() throws Exception {
        var localLock = new ReentrantLock();
        localLock.lock();
        try (FileChannel channel = FileChannel.open(temporaryDirectory.resolve("symbols.mv.db.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            IOException releaseFailure = new IOException("release failed");
            DatabaseLock lock = exclusiveLock(localLock, channel, new FailingFileLock(channel, releaseFailure));

            InvocationTargetException invocation = assertThrows(InvocationTargetException.class, () -> DatabaseLock.class.getMethod("close").invoke(lock));

            assertSame(releaseFailure, invocation.getCause());
            assertFalse(channel.isOpen());
            assertFalse(localLock.isLocked());
        }
    }

    private static DatabaseLock exclusiveLock(ReentrantLock localLock, FileChannel channel, FileLock fileLock) throws Exception {
        var constructor = DatabaseLock.class.getDeclaredConstructor(java.util.concurrent.locks.Lock.class, DatabaseLockState.class, boolean.class, FileChannel.class, FileLock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(localLock, new DatabaseLockState(), false, channel, fileLock);
    }

    private static final class FailingFileLock extends FileLock {
        private final IOException failure;

        FailingFileLock(FileChannel channel, IOException failure) {
            super(channel, 0, Long.MAX_VALUE, false);
            this.failure = failure;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void release() throws IOException {
            throw failure;
        }
    }
}
