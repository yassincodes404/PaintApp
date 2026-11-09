package com.paintstudio.settings;

import com.paintstudio.common.concurrent.BackgroundJobs;
import com.paintstudio.common.logging.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Apply is asynchronous; listeners are dispatched by the supplied UI dispatcher. */
public final class SettingsManager {
  private final SettingsStore store;
  private final BackgroundJobs jobs;
  private final Consumer<Runnable> dispatch;
  private final List<Consumer<UserSettings>> listeners = new ArrayList<>();
  private volatile UserSettings current;

  public SettingsManager(
      SettingsStore store, UserSettings loaded, BackgroundJobs jobs, Consumer<Runnable> dispatch) {
    this.store = store;
    this.current = loaded;
    this.jobs = jobs;
    this.dispatch = dispatch;
  }

  public UserSettings current() {
    return current;
  }

  public void listen(Consumer<UserSettings> listener) {
    listeners.add(listener);
  }

  public CompletableFuture<Void> apply(UserSettings next) {
    SettingsValidator.validate(next);
    return jobs.submit(
            () -> {
              store.save(next);
              return next;
            })
        .thenCompose(
            saved -> {
              CompletableFuture<Void> applied = new CompletableFuture<>();
              dispatch.accept(
                  () -> {
                    try {
                      current = saved;
                      listeners.forEach(listener -> listener.accept(saved));
                      AppLogger.getInstance()
                          .info(
                              LogCategory.CONFIGURATION,
                              SettingsManager.class,
                              "settings.applied",
                              Map.of(
                                  "theme",
                                  saved.appearance().theme(),
                                  "autosaveSeconds",
                                  saved.performance().autosaveSeconds()));
                      applied.complete(null);
                    } catch (RuntimeException error) {
                      applied.completeExceptionally(error);
                    }
                  });
              return applied;
            });
  }
}
