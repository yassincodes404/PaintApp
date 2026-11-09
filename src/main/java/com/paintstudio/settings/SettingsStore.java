package com.paintstudio.settings;

import com.google.gson.*;
import com.paintstudio.common.io.AtomicFiles;
import com.paintstudio.common.logging.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned settings; unknown future versions are read-only to avoid destructive downgrade. */
public final class SettingsStore {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final Path file;
  private boolean readOnly;
  private final boolean migrate;

  public SettingsStore(Path directory) {
    this(directory, true);
  }

  public SettingsStore(Path directory, boolean migrate) {
    file = directory.resolve("settings.json");
    this.migrate = migrate;
  }

  public UserSettings load(UserSettings defaults) {
    try {
      if (Files.exists(file)) {
        if (Files.size(file) > 65536) throw new IOException("Settings file exceeds 64 KiB");
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (saved.has("schemaVersion") && saved.get("schemaVersion").getAsInt() != 1) {
          readOnly = true;
          throw new IOException("Newer/unsupported settings version; file preserved read-only");
        }
        JsonObject effective = JSON.toJsonTree(defaults).getAsJsonObject();
        merge(effective, saved);
        return SettingsValidator.validate(JSON.fromJson(effective, UserSettings.class));
      }
      Path legacy = file.resolveSibling("preferences.properties");
      if (Files.exists(legacy)) {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(legacy)) {
          p.load(reader);
        }
        String theme = p.getProperty("theme", defaults.appearance().theme());
        UserSettings migrated =
            new UserSettings(
                1,
                new UserSettings.Appearance(
                    theme, defaults.appearance().showSplash(), defaults.appearance().showGrid()),
                defaults.canvas(),
                defaults.performance());
        SettingsValidator.validate(migrated);
        if (migrate) save(migrated);
        return migrated;
      }
    } catch (IOException | RuntimeException error) {
      // Preserve corrupt input; never replace it silently on startup.
      AppLogger.getInstance()
          .warn(
              LogCategory.CONFIGURATION,
              SettingsStore.class,
              "settings.load.fallback",
              Map.of("reason", String.valueOf(error.getMessage())));
    }
    return defaults;
  }

  private static void merge(JsonObject base, JsonObject saved) {
    for (var entry : saved.entrySet()) {
      if (entry.getValue().isJsonNull()) continue;
      if (base.has(entry.getKey())
          && base.get(entry.getKey()).isJsonObject()
          && entry.getValue().isJsonObject())
        merge(base.getAsJsonObject(entry.getKey()), entry.getValue().getAsJsonObject());
      else base.add(entry.getKey(), entry.getValue());
    }
  }

  public void save(UserSettings settings) throws IOException {
    SettingsValidator.validate(settings);
    if (readOnly)
      throw new IOException("Settings were created by an unsupported application version");
    if (Files.exists(file))
      Files.copy(
          file, file.resolveSibling("settings.json.bak"), StandardCopyOption.REPLACE_EXISTING);
    AtomicFiles.write(
        file, out -> out.write(JSON.toJson(settings).getBytes(StandardCharsets.UTF_8)));
  }
}
