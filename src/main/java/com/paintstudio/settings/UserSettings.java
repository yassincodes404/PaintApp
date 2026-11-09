package com.paintstudio.settings;

/** Persisted values contain no JavaFX objects. Values are validated before use. */
public record UserSettings(
    int schemaVersion, Appearance appearance, Canvas canvas, Performance performance) {
  public record Appearance(
      String theme,
      boolean showSplash,
      boolean showGrid,
      boolean showWelcome,
      boolean fullScreenFocus,
      boolean rememberRecent) {
    public Appearance(String theme, boolean showSplash, boolean showGrid) {
      this(theme, showSplash, showGrid, true, false, true);
    }
  }

  public record Canvas(
      int width,
      int height,
      String background,
      boolean autoExpand,
      int expansionStep,
      int expansionMargin,
      double minZoom,
      double maxZoom) {}

  public record Performance(
      int undoMiB,
      int undoCommands,
      int autosaveSeconds,
      int recoveryGenerations,
      int recoveryMiB) {}
}
