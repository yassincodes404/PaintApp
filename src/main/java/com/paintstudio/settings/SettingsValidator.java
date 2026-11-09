package com.paintstudio.settings;

import java.util.Set;

public final class SettingsValidator {
  private SettingsValidator() {}

  public static UserSettings validate(UserSettings s) {
    if (s == null
        || s.schemaVersion() != 1
        || s.appearance() == null
        || s.canvas() == null
        || s.performance() == null)
      throw new IllegalArgumentException("Unsupported settings schema");
    var a = s.appearance();
    var c = s.canvas();
    var p = s.performance();
    if (a.theme() == null || !Set.of("dark", "light").contains(a.theme()))
      throw new IllegalArgumentException("Theme must be dark or light");
    range(c.width(), 256, 32768, "Canvas width");
    range(c.height(), 256, 32768, "Canvas height");
    if (c.background() == null || !c.background().matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
      throw new IllegalArgumentException("Background must be #RRGGBB or #RRGGBBAA");
    range(c.expansionStep(), 64, 4096, "Expansion step");
    range(c.expansionMargin(), 0, c.expansionStep(), "Expansion margin");
    if (!Double.isFinite(c.minZoom())
        || !Double.isFinite(c.maxZoom())
        || c.minZoom() < .01
        || c.maxZoom() > 64
        || c.minZoom() > 1
        || c.maxZoom() < 1) throw new IllegalArgumentException("Invalid zoom limits");
    range(p.undoMiB(), 1, 256, "History memory");
    range(p.undoCommands(), 1, 10000, "History count");
    if (p.autosaveSeconds() != 0) range(p.autosaveSeconds(), 15, 3600, "Autosave interval");
    range(p.recoveryGenerations(), 1, 10, "Recovery generations");
    range(p.recoveryMiB(), 1, 512, "Recovery disk budget");
    return s;
  }

  private static void range(int value, int min, int max, String name) {
    if (value < min || value > max)
      throw new IllegalArgumentException(name + " must be " + min + "–" + max);
  }
}
