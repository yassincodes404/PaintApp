package com.paintstudio.app;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.settings.SettingsStore;
import java.util.*;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;

/** Owns CSS installation only; settings persistence belongs to SettingsManager. */
public final class ThemeManager {
  private static final Set<Parent> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
  private static volatile String theme;

  private ThemeManager() {}

  public static void initialize(String selected) {
    theme = selected;
  }

  public static String currentTheme() {
    if (theme == null)
      theme =
          new SettingsStore(AppPaths.config(), false)
              .load(EditorConfig.load().defaults())
              .appearance()
              .theme();
    return theme;
  }

  public static void apply(Scene scene) {
    apply(scene.getRoot());
  }

  public static void apply(Parent root) {
    requireFxThread();
    ROOTS.add(root);
    install(root);
  }

  public static void setTheme(String selected) {
    requireFxThread();
    if (!Set.of("dark", "light").contains(selected))
      throw new IllegalArgumentException("Unknown theme");
    theme = selected;
    ROOTS.forEach(ThemeManager::install);
  }

  private static void install(Parent root) {
    var sheets = root.getStylesheets();
    for (String name : List.of("base", "dark", "light", "components")) {
      String resource =
          Objects.requireNonNull(ThemeManager.class.getResource("/css/" + name + ".css"))
              .toExternalForm();
      sheets.remove(resource);
    }
    for (String name : List.of("base", currentTheme(), "components"))
      sheets.add(
          Objects.requireNonNull(ThemeManager.class.getResource("/css/" + name + ".css"))
              .toExternalForm());
  }

  private static void requireFxThread() {
    if (!Platform.isFxApplicationThread())
      throw new IllegalStateException("Theme changes require the FX thread");
  }
}
