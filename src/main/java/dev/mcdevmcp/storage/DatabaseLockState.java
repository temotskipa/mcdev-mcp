package dev.mcdevmcp.storage;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class DatabaseLockState {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    final Object sharedGuard = new Object();
    int sharedReferences;
    FileChannel sharedChannel;
    FileLock sharedFileLock;
}
