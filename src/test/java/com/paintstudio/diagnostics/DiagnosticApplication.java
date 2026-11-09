package com.paintstudio.diagnostics;

import com.paintstudio.app.PaintApplication;
import com.paintstudio.common.logging.*;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.CssParser;
import javafx.stage.Stage;

/** Test-only launcher: real application lifecycle, periodic JVM samples, controlled exit. */
public final class DiagnosticApplication {
  public static void main(String[] args) {
    if (args.length == 1 && args[0].equals("fail-initialization")) {
      Application.launch(FailedInitialization.class);
      return;
    }
    var cssErrors = CssParser.errorsProperty();
    var sampler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "diagnostics-sampler");
              thread.setDaemon(true);
              return thread;
            });
    sampler.scheduleAtFixedRate(
        () -> {
          var logger = AppLogger.getInstance();
          if (!logger.accepts(LogLevel.INFO, LogCategory.PERFORMANCE)) return;
          var memory = ManagementFactory.getMemoryMXBean();
          var heap = memory.getHeapMemoryUsage();
          var threads = ManagementFactory.getThreadMXBean();
          var values = new LinkedHashMap<String, Object>();
          values.put("sampleTime", Instant.now().toString());
          values.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
          values.put("heapUsedBytes", heap.getUsed());
          values.put("heapCommittedBytes", heap.getCommitted());
          values.put("heapMaxBytes", heap.getMax());
          values.put("nonHeapUsedBytes", memory.getNonHeapMemoryUsage().getUsed());
          values.put("threadCount", threads.getThreadCount());
          values.put("peakThreadCount", threads.getPeakThreadCount());
          long collections = 0, gcMillis = 0;
          for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, collector.getCollectionCount());
            gcMillis += Math.max(0, collector.getCollectionTime());
          }
          values.put("gcCount", collections);
          values.put("gcTimeMs", gcMillis);
          if (ManagementFactory.getOperatingSystemMXBean()
              instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            values.put("processCpuTimeNs", operatingSystem.getProcessCpuTime());
          }
          logger.info(
              LogCategory.PERFORMANCE,
              DiagnosticApplication.class,
              "diagnostics.jvm.sample",
              values);
        },
        0,
        1,
        TimeUnit.SECONDS);
    Thread control =
        new Thread(
            () -> {
              try {
                int input;
                while ((input = System.in.read()) >= 0) {
                  if (input == 'w') {
                    EditorWorkload.start();
                    continue;
                  }
                  Platform.runLater(
                      () -> {
                        cssErrors.forEach(
                            error -> System.err.println("JavaFX CSS error: " + error));
                        Platform.exit();
                      });
                  break;
                }
              } catch (Exception error) {
                error.printStackTrace(System.err);
              }
            },
            "diagnostics-control");
    control.setDaemon(true);
    control.start();
    try {
      Application.launch(PaintApplication.class, args);
    } finally {
      sampler.shutdownNow();
    }
    // The normal stop path already closes the logger. Repeated closes must be safe.
    var logger = AppLogger.getInstance();
    logger.close();
    logger.close();
    if (logger.state() != AppLogger.State.CLOSED) {
      throw new IllegalStateException("Logger did not reach CLOSED after application shutdown");
    }
  }

  public static final class FailedInitialization extends Application {
    @Override
    public void init() {
      new PaintApplication().init();
      throw new IllegalStateException("Diagnostic failure after acquiring the application lock");
    }

    @Override
    public void start(Stage stage) {
      throw new AssertionError("JavaFX started after initialization failed");
    }
  }
}
