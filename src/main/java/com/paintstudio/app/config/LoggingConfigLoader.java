package com.paintstudio.app.config;

import com.paintstudio.app.AppPaths;
import com.paintstudio.common.logging.LogCategory;
import com.paintstudio.common.logging.LogLevel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bootstrap configuration deliberately has no dependency on AppLogger. */
public final class LoggingConfigLoader {
    public record LoadResult(LoggingConfig config, List<String> diagnostics, Map<String, String> origins) {
        public LoadResult {
            diagnostics = List.copyOf(diagnostics);
            origins = Map.copyOf(origins);
        }
    }

    private LoggingConfigLoader() {}

    public static LoadResult load() {
        return load(AppPaths.config().resolve("logging.properties"), System.getProperties());
    }

    public static LoadResult load(Path userFile, Properties system) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        String defaults = """
                enabled=true
                level=INFO
                console.enabled=true
                file.enabled=true
                file.format=jsonl
                file.max-size-mb=10
                file.max-files=10
                file.retention-days=14
                queue.capacity=4096
                queue.error-reserve=128
                flush.interval-ms=1000
                shutdown.timeout-ms=3000
                startup.configuration=true
                startup.environment=true
                input.shortcuts=true
                input.key-events=false
                input.typed-text=false
                input.mouse-movement=false
                context.full-paths=false
                context.stack-traces=true
                """;
        defaults.lines().filter(line -> !line.isBlank()).forEach(line -> {
            String[] pair = line.split("=", 2);
            values.put("logging." + pair[0], pair[1]);
        });
        for (LogCategory category : LogCategory.values()) {
            values.put("logging.category." + category.name().toLowerCase(Locale.ROOT),
                    Boolean.toString(category != LogCategory.INPUT && category != LogCategory.PERFORMANCE));
        }
        values.keySet().forEach(key -> origins.put(key, "built-in"));
        try (InputStream stream = LoggingConfigLoader.class.getResourceAsStream("/config/logging.properties")) {
            if (stream != null) overlay(read(stream), "bundled", values, origins, diagnostics);
        } catch (IOException | IllegalArgumentException error) {
            diagnostics.add("Bundled logging configuration could not be read: " + error.getClass().getSimpleName());
        }
        if (Files.exists(userFile)) {
            try (InputStream stream = Files.newInputStream(userFile)) {
                overlay(read(stream), "user", values, origins, diagnostics);
            } catch (IOException | IllegalArgumentException error) {
                diagnostics.add("User logging configuration could not be read: " + error.getClass().getSimpleName());
            }
        }
        Properties overrides = new Properties();
        for (String key : system.stringPropertyNames()) {
            if (key.startsWith("paintstudio.logging.")) {
                overrides.setProperty(key.substring("paintstudio.".length()), system.getProperty(key));
            }
        }
        overlay(overrides, "JVM", values, origins, diagnostics);
        return new LoadResult(new LoggingConfig(values), diagnostics, origins);
    }

    private static Properties read(InputStream stream) throws IOException {
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        return properties;
    }

    private static void overlay(Properties source, String origin, Map<String, String> values,
            Map<String, String> origins, List<String> diagnostics) {
        Map<String, String> candidate = new LinkedHashMap<>(values);
        Set<String> accepted = new HashSet<>();
        for (String key : source.stringPropertyNames()) {
            String value = source.getProperty(key).trim();
            if (!values.containsKey(key)) {
                diagnostics.add("Unknown logging key: " + key);
                continue;
            }
            if (!valid(key, value, values.get(key))) {
                diagnostics.add("Invalid " + key + "; retained previous valid setting");
                continue;
            }
            candidate.put(key, value);
            accepted.add(key);
        }
        if (Integer.parseInt(candidate.get("logging.queue.error-reserve"))
                >= Integer.parseInt(candidate.get("logging.queue.capacity"))) {
            diagnostics.add("Queue reserve must be smaller than capacity; retained previous queue settings");
            accepted.remove("logging.queue.capacity");
            accepted.remove("logging.queue.error-reserve");
        }
        for (String key : accepted) {
            values.put(key, candidate.get(key));
            origins.put(key, origin);
        }
    }

    private static boolean valid(String key, String value, String previous) {
        if (previous.equals("true") || previous.equals("false")) {
            return value.equals("true") || value.equals("false");
        }
        if (key.equals("logging.level")) {
            try { LogLevel.valueOf(value); return true; }
            catch (IllegalArgumentException error) { return false; }
        }
        if (key.equals("logging.file.format")) return value.equals("jsonl");
        try {
            int number = Integer.parseInt(value);
            int minimum = key.endsWith("error-reserve") ? 0 : 1;
            // Explicit upper bounds protect memory and shutdown responsiveness.
            int maximum = key.endsWith("queue.capacity") ? 65536 : 100000;
            if (key.endsWith("shutdown.timeout-ms")) maximum = 30000;
            return number >= minimum && number <= maximum;
        } catch (NumberFormatException error) {
            return false;
        }
    }
}
