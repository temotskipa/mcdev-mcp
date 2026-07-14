package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;

record DatabaseFileHandle(FileChannel channel, boolean reservationCreated) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        channel.close();
    }
}
