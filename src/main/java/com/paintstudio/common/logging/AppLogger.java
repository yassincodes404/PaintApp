package com.paintstudio.common.logging;

import com.paintstudio.app.AppPaths;
import com.paintstudio.app.config.LoggingConfig;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One application service; construction has no I/O or thread side effects. */
public final class AppLogger implements AutoCloseable {
    public enum State { DISABLED, RUNNING, STOPPING, FAILED, CLOSED }
    private static final AppLogger INSTANCE = new AppLogger();
    private final Object admission = new Object();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private volatile State state = State.DISABLED;
    private volatile LoggingConfig config;
    private ArrayBlockingQueue<LogEvent> queue;
    private Thread worker;
    private LogWriter writer;
    private String session;
    private long sequence;

    private AppLogger() {}
    public static AppLogger getInstance() { return INSTANCE; }
    public State state() { return state; }
    public long droppedEvents() { return totalDropped.get(); }
    public LoggingConfig configuration() { return config; }

    public synchronized void configure(LoggingConfig config) {
        Objects.requireNonNull(config);
        if (state == State.CLOSED) throw new IllegalStateException("Logger is closed");
        if (worker != null && worker.isAlive()) throw new IllegalStateException("Disable the logger before configuring");
        this.config = config;
    }

    public synchronized void start() {
        if (state == State.CLOSED) throw new IllegalStateException("Logger is closed");
        if (worker != null && worker.isAlive()) return;
        if (config == null) throw new IllegalStateException("Configure logging first");
        if (!config.enabled()) { state = State.DISABLED; return; }
        synchronized (admission) {
            session = UUID.randomUUID().toString();
            queue = new ArrayBlockingQueue<>(config.number("queue.capacity"));
            writer = new LogWriter(config, AppPaths.logs(), session, queue, () -> dropped.getAndSet(0),
                    () -> {
                        synchronized (admission) {
                            if (state == State.RUNNING) state = State.FAILED;
                        }
                    });
            worker = new Thread(writer, "paint-studio-logger");
            worker.setDaemon(true);
            state = State.RUNNING;
            worker.start();
        }
        info(LogCategory.SYSTEM, AppLogger.class, "logging.started", Map.of("directory",
                config.flag("context.full-paths") ? AppPaths.logs().toString() : "PaintStudio/logs"));
    }

    public synchronized void reconfigure(LoggingConfig next) {
        disable();
        if (state == State.STOPPING) throw new IllegalStateException("Previous writer is still stopping");
        configure(next);
        start();
    }

    public synchronized void disable() {
        if (state == State.CLOSED) return;
        synchronized (admission) {
            if (worker == null || !worker.isAlive()) { state = State.DISABLED; return; }
            state = State.STOPPING;
            writer.stop();
        }
        try { worker.join(config.number("shutdown.timeout-ms")); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) {
            worker.interrupt();
            System.err.println("Paint Studio logger shutdown deadline exceeded; pending=" + queue.size());
        } else state = State.DISABLED;
    }

    @Override
    public synchronized void close() {
        disable();
        if (state != State.STOPPING) state = State.CLOSED;
    }

    public boolean accepts(LogLevel level, LogCategory category) {
        LoggingConfig current = config;
        return state == State.RUNNING && current != null && current.accepts(level, category);
    }

    public void info(LogCategory category, Class<?> source, String event, Map<String, ?> context) {
        log(LogLevel.INFO, category, source, event, () -> context);
    }

    public void warn(LogCategory category, Class<?> source, String event, Map<String, ?> context) {
        log(LogLevel.WARN, category, source, event, () -> context);
    }

    public void debug(LogCategory category, Class<?> source, String event, Supplier<Map<String, ?>> context) {
        log(LogLevel.DEBUG, category, source, event, context);
    }

    public void error(LogCategory category, Class<?> source, String event, Throwable error) {
        log(LogLevel.ERROR, category, source, event, () -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exception", error.getClass().getName());
            details.put("message", String.valueOf(error.getMessage()));
            if (config.flag("context.stack-traces")) {
                StringBuilder trace = new StringBuilder();
                Throwable current = error;
                Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                while (current != null && seen.add(current) && trace.length() < 8192) {
                    trace.append(current).append('\n');
                    for (StackTraceElement frame : current.getStackTrace()) {
                        if (trace.length() >= 8192) break;
                        trace.append("  at ").append(frame).append('\n');
                    }
                    current = current.getCause();
                }
                details.put("stackTrace", JsonLogFormatter.text(trace.toString()));
            }
            return details;
        });
    }

    public void log(LogLevel level, LogCategory category, Class<?> source, String event,
            Supplier<Map<String, ?>> context) {
        if (!accepts(level, category)) return;
        Map<String, Object> snapshot;
        try { snapshot = JsonLogFormatter.snapshot(context.get()); }
        catch (RuntimeException error) {
            snapshot = Map.of("contextError", error.getClass().getSimpleName());
        }
        synchronized (admission) {
            if (!accepts(level, category)) return;
            boolean reserved = level.ordinal() < LogLevel.WARN.ordinal()
                    && queue.remainingCapacity() <= config.number("queue.error-reserve");
            if (reserved || !queue.offer(new LogEvent(Instant.now(), ++sequence, session, level, category,
                    source.getName(), JsonLogFormatter.text(event), Thread.currentThread().getName(), snapshot))) {
                dropped.incrementAndGet();
                totalDropped.incrementAndGet();
            } else {
                writer.signalWork();
            }
        }
    }
}
