package com.paintstudio.app.config;

import com.paintstudio.common.logging.*;
import com.paintstudio.settings.*;
import java.io.*;
import java.util.*;

/** Resource policy is separate from document data and user-editable preferences. */
public record EditorConfig(
    UserSettings defaults,
    int ioQueueCapacity,
    int shutdownSeconds,
    int maxObjects,
    int maxPoints,
    int maxStrokePoints,
    int maxFileMiB,
    long maxExportPixels,
    double coordinateLimit,
    double inputSpacing) {
  public static EditorConfig load() {
    Properties p = new Properties();
    try (var input = EditorConfig.class.getResourceAsStream("/config/editor.properties")) {
      if (input != null) p.load(input);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot load editor defaults", error);
    }
    var defaults =
        new UserSettings(
            1,
            new UserSettings.Appearance(
                ConfigLoader.load().defaultTheme(),
                true,
                Boolean.parseBoolean(p.getProperty("canvas.grid", "false"))),
            new UserSettings.Canvas(
                n(p, "canvas.width", 4096),
                n(p, "canvas.height", 4096),
                p.getProperty("canvas.background", "#201F1E"),
                Boolean.parseBoolean(p.getProperty("canvas.auto-expand", "true")),
                n(p, "canvas.expansion.step", 512),
                n(p, "canvas.expansion.margin", 128),
                Double.parseDouble(p.getProperty("canvas.zoom.min", "0.05")),
                Double.parseDouble(p.getProperty("canvas.zoom.max", "16"))),
            new UserSettings.Performance(
                n(p, "history.memory.mib", 128),
                n(p, "history.commands", 1000),
                n(p, "recovery.interval.seconds", 120),
                n(p, "recovery.generations", 3),
                n(p, "recovery.max.mib", 128)));
    SettingsValidator.validate(defaults);
    return new EditorConfig(
        defaults,
        n(p, "io.queue.capacity", 8),
        n(p, "io.shutdown.seconds", 10),
        n(p, "document.max.objects", 100000),
        n(p, "document.max.points", 2000000),
        n(p, "stroke.max.points", 20000),
        n(p, "project.max.mib", 128),
        n(p, "export.max.pixels", 16777216),
        n(p, "document.coordinate.limit", 1000000),
        Double.parseDouble(p.getProperty("input.spacing", "0.75")));
  }

  public EditorConfig {
    SettingsValidator.validate(defaults);
    if (ioQueueCapacity < 1
        || ioQueueCapacity > 64
        || shutdownSeconds < 1
        || shutdownSeconds > 60
        || maxObjects < 1
        || maxObjects > 1000000
        || maxPoints < 1
        || maxPoints > 4000000
        || maxStrokePoints < 2
        || maxStrokePoints > maxPoints
        || maxFileMiB < 1
        || maxFileMiB > 128
        || maxExportPixels < 1
        || maxExportPixels > 32000000
        || !Double.isFinite(coordinateLimit)
        || coordinateLimit < 32768
        || coordinateLimit > 10000000
        || !Double.isFinite(inputSpacing)
        || inputSpacing < .1
        || inputSpacing > 10) throw new IllegalArgumentException("Invalid editor resource policy");
  }

  private static int n(Properties p, String key, int fallback) {
    return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
  }
}
