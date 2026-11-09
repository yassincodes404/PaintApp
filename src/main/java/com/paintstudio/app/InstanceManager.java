package com.paintstudio.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive per-user application ownership. Keep the file in place after release.
 * See FileChannel.tryLock and FileLock in the Java 25 API documentation.
 */
public final class InstanceManager implements AutoCloseable {
    private static InstanceManager owner;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceManager(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns null when already running; I/O failures must prevent startup. */
    public static synchronized InstanceManager tryAcquire() throws IOException {
        // Avoid opening a second channel to a file locked by this JVM.
        if (owner != null && owner.lock.isValid()) return null;
        var path = AppPaths.instanceLock();
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock(0, 1, false);
            if (lock == null) {
                channel.close();
                return null;
            }
            owner = new InstanceManager(channel, lock);
            return owner;
        } catch (IOException | RuntimeException error) {
            try { channel.close(); }
            catch (IOException closingError) { error.addSuppressed(closingError); }
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (InstanceManager.class) {
            // Closing the sole channel releases its lock, including on repeated close.
            channel.close();
            if (owner == this) owner = null;
        }
    }
}
