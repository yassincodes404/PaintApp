package com.paintstudio.common.concurrent;

import com.paintstudio.common.logging.*;
import java.util.concurrent.*;

/** One lazily created worker; bounded admission never executes disk I/O on the caller. */
public final class BackgroundJobs implements AutoCloseable {
  private final ThreadPoolExecutor executor;
  private final int shutdownSeconds;

  public BackgroundJobs(int capacity, int shutdownSeconds) {
    this.shutdownSeconds = shutdownSeconds;
    executor =
        new ThreadPoolExecutor(
            0,
            1,
            20,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(capacity),
            task -> {
              Thread thread = new Thread(task, "paint-studio-io");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public <T> CompletableFuture<T> submit(Callable<T> action) {
    CompletableFuture<T> future = new CompletableFuture<>();
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              if (!future.isCancelled()) {
                try {
                  future.complete(action.call());
                } catch (Throwable error) {
                  future.completeExceptionally(error);
                  AppLogger.getInstance()
                      .error(LogCategory.SYSTEM, BackgroundJobs.class, "background.failed", error);
                }
              }
              return null;
            });
    future.whenComplete(
        (value, error) -> {
          if (future.isCancelled()) {
            task.cancel(true);
            executor.remove(task);
          }
        });
    try {
      executor.execute(task);
    } catch (RejectedExecutionException error) {
      future.completeExceptionally(error);
    }
    return future;
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        AppLogger.getInstance()
            .warn(
                LogCategory.SYSTEM,
                BackgroundJobs.class,
                "background.shutdown.timeout",
                java.util.Map.of());
      }
    } catch (InterruptedException error) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
