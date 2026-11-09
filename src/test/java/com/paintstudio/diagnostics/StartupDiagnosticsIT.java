package com.paintstudio.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Run with mvn -Pdiagnostics verify on a machine with a graphical desktop. */
class StartupDiagnosticsIT {
  private final Path javaHome = Path.of(System.getProperty("java.home"));
  private final boolean windows = System.getProperty("os.name").startsWith("Windows");

  @Test
  @Timeout(120)
  void recordsStartupMemoryThreadsAndCleanShutdown() throws Exception {
    Path output =
        Files.createDirectories(
            Path.of(
                    "target",
                    "diagnostics",
                    Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID())
                .toAbsolutePath());
    Path console = output.resolve("application-console.log");
    Path recording = output.resolve("startup.jfr");
    Path isolatedHome = Files.createDirectories(output.resolve("test-home"));
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    long heapLimitMiB = Long.getLong("paintstudio.heap.max.mib", 1024);
    List<String> command =
        List.of(
            tool("java"),
            "-Xmx" + heapLimitMiB + "m",
            "-XX:NativeMemoryTracking=summary",
            "-XX:StartFlightRecording=name=PaintStudioTest,settings=profile,maxsize=32m,dumponexit=true,filename="
                + recording,
            "-Duser.home=" + isolatedHome,
            "-Dpaintstudio.logging.category.performance=true",
            "--module-path",
            javafxModulePath(),
            "--add-modules",
            "javafx.controls",
            "--enable-native-access=javafx.graphics,ALL-UNNAMED",
            "-cp",
            classpath,
            DiagnosticApplication.class.getName());
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().put("LOCALAPPDATA", isolatedHome.toString());
    builder.redirectErrorStream(true).redirectOutput(console.toFile());
    Files.writeString(
        output.resolve("run.properties"),
        "started="
            + Instant.now()
            + "\njava.home="
            + javaHome
            + "\nscenario=startup-editor-and-idle\n"
            + "heap.max.mib="
            + heapLimitMiB
            + "\n"
            + "sample.interval.ms=1000\nidle.observation.seconds=5\n");
    Process application = builder.start();
    try {
      awaitReady(application, console);
      diagnostic(output, application.pid(), "native-baseline.txt", "VM.native_memory", "baseline");
      diagnostic(output, application.pid(), "native-start.txt", "VM.native_memory", "summary");
      diagnostic(output, application.pid(), "threads.txt", "Thread.print", "-l");
      diagnostic(output, application.pid(), "heap-start.txt", "GC.heap_info");
      application.getOutputStream().write('w');
      application.getOutputStream().flush();
      long workloadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!read(console).contains("EDITOR_WORKLOAD_PASS")
          && System.nanoTime() < workloadDeadline) {
        assertTrue(application.isAlive(), () -> read(console));
        assertFalse(read(console).contains("EDITOR_WORKLOAD_FAILED"), () -> read(console));
        Thread.sleep(100);
      }
      assertTrue(read(console).contains("EDITOR_WORKLOAD_PASS"), () -> read(console));
      diagnostic(output, application.pid(), "heap-after-editor.txt", "GC.heap_info");
      // Deliberate fixed observation window after readiness, not a startup readiness assumption.
      assertFalse(
          application.waitFor(5, TimeUnit.SECONDS), "Application exited during observation");
      diagnostic(output, application.pid(), "heap-idle.txt", "GC.heap_info");
      diagnostic(output, application.pid(), "native-diff.txt", "VM.native_memory", "summary.diff");
      diagnostic(output, application.pid(), "jfr-status.txt", "JFR.check");
      application.getOutputStream().write('\n');
      application.getOutputStream().flush();
      assertTrue(application.waitFor(15, TimeUnit.SECONDS), "Application did not shut down");
      assertEquals(0, application.exitValue(), () -> read(console));
      String consoleOutput = read(console);
      assertFalse(consoleOutput.contains("Unsupported JavaFX configuration"), () -> consoleOutput);
      assertFalse(consoleOutput.contains("WARNING: A restricted method"), () -> consoleOutput);
      assertFalse(consoleOutput.contains("JavaFX CSS error:"), () -> consoleOutput);
      if (System.getProperty("os.name").startsWith("Windows")
          && java.lang.foreign.ValueLayout.ADDRESS.byteSize() == 8) {
        assertTrue(
            consoleOutput.contains("\"event\":\"tablet.bridge.ready\""), () -> consoleOutput);
      }
      assertInstanceLockReleased(isolatedHome);
      assertTrue(Files.size(recording) > 0, "No JFR recording");
      long events = 0;
      Set<String> eventTypes = new TreeSet<>();
      try (RecordingFile reader = new RecordingFile(recording)) {
        while (reader.hasMoreEvents()) {
          eventTypes.add(reader.readEvent().getEventType().getName());
          events++;
        }
      }
      assertTrue(events > 0, "JFR recording contains no events");
      Files.write(output.resolve("jfr-event-types.txt"), eventTypes);
      String logs;
      Path logDirectory = isolatedHome.resolve("PaintStudio/logs");
      try (var paths = Files.list(logDirectory)) {
        StringBuilder combined = new StringBuilder();
        for (Path path :
            paths.filter(path -> path.toString().endsWith(".jsonl")).sorted().toList()) {
          combined.append(Files.readString(path));
        }
        logs = combined.toString();
      }
      Files.writeString(output.resolve("application.jsonl"), logs);
      List<String> samples =
          logs.lines()
              .filter(line -> line.contains("\"event\":\"diagnostics.jvm.sample\""))
              .toList();
      Files.write(output.resolve("memory-samples.jsonl"), samples);
      assertTrue(samples.size() >= 3, "Insufficient JVM samples");
      var heapMax = Pattern.compile("\"heapMaxBytes\":(\\d+)").matcher(samples.getFirst());
      assertTrue(heapMax.find(), "Missing maximum heap sample");
      long effectiveHeapMax = Long.parseLong(heapMax.group(1));
      assertTrue(
          effectiveHeapMax <= Math.multiplyExact(heapLimitMiB, 1024L * 1024),
          "Child JVM exceeded the configured heap cap: " + effectiveHeapMax);
      int splashShown = logs.indexOf("\"event\":\"splash.shown\"");
      int preparationStarted = logs.indexOf("\"event\":\"startup.preparation.started\"");
      int preparationCompleted = logs.indexOf("\"event\":\"startup.preparation.completed\"");
      int splashHidden = logs.indexOf("\"event\":\"splash.hidden\"");
      int editorReady = logs.indexOf("\"event\":\"application.ready\"");
      assertTrue(
          splashShown >= 0
              && splashShown < preparationStarted
              && preparationStarted < preparationCompleted
              && preparationCompleted < splashHidden
              && splashHidden < editorReady,
          "Expected splash to cover startup preparation before showing the editor");
      assertTrue(logs.contains("\"event\":\"application.stopping\""), "Missing normal shutdown");
      assertTrue(read(output.resolve("threads.txt")).contains("JavaFX Application Thread"));
      assertTrue(read(output.resolve("native-diff.txt")).contains("Native Memory Tracking"));
      verifyFailedInitialization(command, output);
      Files.writeString(
          output.resolve("summary.txt"),
          "PASS\nJFR events="
              + events
              + "\nMemory samples="
              + samples.size()
              + "\nEffective maximum heap bytes="
              + effectiveHeapMax
              + "\nExit code=0\nFailed initialization lock release=PASS\nCompleted="
              + Instant.now()
              + "\n"
              + "Editor workload=PASS (100 strokes, undo/redo, save/reopen, PNG, 20 model"
              + " lifetimes)\n"
              + "This bounded observation is not a memory-leak or performance guarantee.\n");
      System.out.println("Diagnostics artifacts: " + output);
    } finally {
      if (application.isAlive()) {
        application.destroy();
        if (!application.waitFor(3, TimeUnit.SECONDS)) {
          application.destroyForcibly();
          application.waitFor(3, TimeUnit.SECONDS);
        }
      }
    }
  }

  private void verifyFailedInitialization(List<String> startupCommand, Path output)
      throws Exception {
    Path home = Files.createDirectories(output.resolve("failed-init-home"));
    Path console = output.resolve("failed-init-console.log");
    List<String> command =
        new ArrayList<>(
            startupCommand.stream()
                .filter(option -> !option.startsWith("-XX:StartFlightRecording"))
                .filter(option -> !option.startsWith("-Duser.home="))
                .toList());
    command.add(1, "-Duser.home=" + home);
    command.add("fail-initialization");
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().put("LOCALAPPDATA", home.toString());
    Process process = builder.redirectErrorStream(true).redirectOutput(console.toFile()).start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Failed initialization did not exit");
      assertNotEquals(0, process.exitValue(), "Expected initialization to fail");
      assertTrue(
          read(console).contains("Diagnostic failure after acquiring the application lock"),
          () -> read(console));
      assertInstanceLockReleased(home);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static void assertInstanceLockReleased(Path home) throws Exception {
    Path instanceLock = home.resolve("PaintStudio/state/application.lock");
    try (var channel = FileChannel.open(instanceLock, StandardOpenOption.WRITE);
        var lock = channel.tryLock(0, 1, false)) {
      assertNotNull(lock, "Application instance lock is still held");
    }
  }

  private void awaitReady(Process process, Path console) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    while (System.nanoTime() < deadline) {
      assertTrue(process.isAlive(), () -> "Startup failed:\n" + read(console));
      if (read(console).contains("\"event\":\"application.ready\"")) return;
      Thread.sleep(100);
    }
    fail("Startup did not complete:\n" + read(console));
  }

  private void diagnostic(Path output, long pid, String file, String... arguments)
      throws Exception {
    List<String> command = new ArrayList<>(List.of(tool("jcmd"), Long.toString(pid)));
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output.resolve(file).toFile())
            .start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Diagnostic command timed out: " + file);
      assertEquals(0, process.exitValue(), () -> read(output.resolve(file)));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static String javafxModulePath() throws java.net.URISyntaxException {
    StringJoiner modulePath = new StringJoiner(File.pathSeparator);
    for (Class<?> type :
        List.of(
            javafx.beans.Observable.class,
            javafx.application.Application.class,
            javafx.scene.control.Control.class)) {
      // Use Maven's resolved platform JARs, excluding the empty unclassified artifacts.
      modulePath.add(
          Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
    return modulePath.toString();
  }

  private String tool(String name) {
    return javaHome.resolve("bin").resolve(name + (windows ? ".exe" : "")).toString();
  }

  private static String read(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file) : "";
    } catch (java.io.IOException error) {
      return error.toString();
    }
  }
}
