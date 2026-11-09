package com.paintstudio.common.logging;

import com.paintstudio.app.config.LoggingConfig;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A single worker owns all output handles. */
final class LogWriter implements Runnable {
    private final LoggingConfig config;
    private final Path directory;
    private final String session;
    private final ArrayBlockingQueue<LogEvent> queue;
    private final LongSupplier takeDropped;
    private final Runnable finished;
    private volatile boolean stopping;
    private boolean fileEnabled;
    private FileChannel channel;
    private FileLock lock;
    private int part;
    private long bytes;
    private boolean pendingFlush;

    LogWriter(LoggingConfig config, Path directory, String session, ArrayBlockingQueue<LogEvent> queue,
            LongSupplier takeDropped, Runnable finished) {
        this.config = config;
        this.directory = directory;
        this.session = session;
        this.queue = queue;
        this.takeDropped = takeDropped;
        this.finished = finished;
        fileEnabled = config.flag("file.enabled");
    }

    synchronized void stop() {
        stopping = true;
        notify();
    }

    synchronized void signalWork() {
        notify();
    }

    private synchronized void awaitWork(long nanosUntilFlush) throws InterruptedException {
        if (!stopping && queue.isEmpty() && nanosUntilFlush > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, nanosUntilFlush);
        }
    }

    @Override
    public void run() {
        long flushInterval = TimeUnit.MILLISECONDS.toNanos(config.number("flush.interval-ms"));
        long nextFlush = System.nanoTime() + flushInterval;
        try {
            if (fileEnabled) {
                try { open(); }
                catch (IOException error) { fileFailed(error); }
            }
            while (!stopping || !queue.isEmpty()) {
                if (!fileEnabled && !config.flag("console.enabled")) {
                    throw new IllegalStateException("No usable logging destination");
                }
                LogEvent event = queue.poll();
                if (event != null) {
                    write(JsonLogFormatter.format(event));
                    if (event.level() == LogLevel.ERROR) flush();
                }
                long now = System.nanoTime();
                if (now - nextFlush >= 0) {
                    long dropped = takeDropped.getAsLong();
                    if (dropped > 0) {
                        write(JsonLogFormatter.format(new LogEvent(Instant.now(), 0, session,
                                LogLevel.WARN, LogCategory.SYSTEM, "AppLogger", "logging.events.dropped",
                                Thread.currentThread().getName(), Map.of("count", dropped))));
                    }
                    flush();
                    nextFlush = System.nanoTime() + flushInterval;
                }
                if (event == null) awaitWork(nextFlush - System.nanoTime());
            }
            long dropped = takeDropped.getAsLong();
            if (dropped > 0) write(JsonLogFormatter.format(new LogEvent(Instant.now(), 0, session,
                    LogLevel.WARN, LogCategory.SYSTEM, "AppLogger", "logging.events.dropped",
                    Thread.currentThread().getName(), Map.of("count", dropped))));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Paint Studio logging interrupted; queued events abandoned: " + queue.size());
        } catch (RuntimeException error) {
            System.err.println("Paint Studio log worker failed: " + error);
        } finally {
            flush();
            closeFile();
            if (fileEnabled) cleanup();
            finished.run();
        }
    }

    private void write(String record) {
        if (config.flag("console.enabled")) System.out.println(record);
        if (!fileEnabled) return;
        try {
            byte[] data = (record + "\n").getBytes(StandardCharsets.UTF_8);
            long limit = config.number("file.max-size-mb") * 1024L * 1024L;
            if (bytes > 0 && bytes + data.length > limit) {
                flush();
                if (!fileEnabled) return;
                closeFile();
                open();
            }
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            bytes += data.length;
            pendingFlush = true;
        } catch (IOException error) { fileFailed(error); }
    }

    private void open() throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("paint-studio-" + LocalDate.now(ZoneOffset.UTC) + "-" + session
                + "-" + part++ + ".jsonl");
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        // Lock a reserved byte outside file contents so live logs remain readable on Windows.
        lock = channel.lock(Long.MAX_VALUE - 1, 1, false);
        bytes = 0;
        cleanup();
    }

    private void flush() {
        if (channel == null || !pendingFlush) return;
        try {
            channel.force(false);
            pendingFlush = false;
        } catch (IOException error) {
            fileFailed(error);
        }
    }

    private void fileFailed(IOException error) {
        fileEnabled = false;
        System.err.println("Paint Studio file logging disabled after output failure: " + error);
        closeFile();
    }

    private void closeFile() {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } catch (IOException error) {
            System.err.println("Cannot release log lock: " + error);
        } finally {
            lock = null;
            try { if (channel != null) channel.close(); }
            catch (IOException error) { System.err.println("Cannot close log file: " + error); }
            channel = null;
        }
    }

    private void cleanup() {
        try (var paths = Files.list(directory)) {
            List<Path> files = paths.filter(path -> path.getFileName().toString().matches(
                    "paint-studio-\\d{4}-\\d{2}-\\d{2}-[a-f0-9-]{36}-\\d+\\.jsonl"))
                    .toList();
            // Use modification time rather than lexicographic part numbers for retention.
            files = new ArrayList<>(files);
            files.sort(Comparator.comparingLong(LogWriter::modified));
            long oldest = System.currentTimeMillis()
                    - TimeUnit.DAYS.toMillis(config.number("file.retention-days"));
            int excess = Math.max(0, files.size() - config.number("file.max-files"));
            for (Path file : files) {
                if (excess == 0 && modified(file) >= oldest) continue;
                if (deleteClosed(file) && excess > 0) excess--;
            }
        } catch (IOException error) {
            System.err.println("Paint Studio log retention failed: " + error.getClass().getSimpleName());
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException error) { return Long.MAX_VALUE; }
    }

    private static boolean deleteClosed(Path path) {
        // Lock the candidate first. A writer never reopens a closed session part.
        try (FileChannel candidate = FileChannel.open(path, StandardOpenOption.WRITE)) {
            try (FileLock candidateLock = candidate.tryLock(Long.MAX_VALUE - 1, 1, false)) {
                if (candidateLock == null) return false;
            }
        } catch (IOException | OverlappingFileLockException error) { return false; }
        try { return Files.deleteIfExists(path); }
        catch (IOException error) { return false; }
    }
}
