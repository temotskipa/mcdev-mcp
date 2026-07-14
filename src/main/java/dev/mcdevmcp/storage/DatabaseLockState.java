package dev.mcdevmcp.storage;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

final class DatabaseLockState {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    final ReentrantLock sharedGuard = new ReentrantLock(true);
    int sharedReferences;
    FileChannel sharedChannel;
    FileLock sharedFileLock;
}
