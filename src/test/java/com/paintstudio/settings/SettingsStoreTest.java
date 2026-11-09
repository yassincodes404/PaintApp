package com.paintstudio.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.common.concurrent.BackgroundJobs;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
  @TempDir Path directory;
  private final UserSettings defaults = EditorConfig.load().defaults();

  @Test
  void fillsMissingDefaultsAndMigratesLegacyPreference() throws Exception {
    Files.writeString(directory.resolve("preferences.properties"), "theme=light");
    var store = new SettingsStore(directory);
    assertEquals("light", store.load(defaults).appearance().theme());
    assertTrue(Files.exists(directory.resolve("settings.json")));
    Files.writeString(
        directory.resolve("settings.json"),
        "{\"schemaVersion\":1,\"appearance\":{\"theme\":\"dark\"}}");
    assertEquals(defaults, store.load(defaults));
  }

  @Test
  void malformedAndInvalidSettingsArePreserved() throws Exception {
    Path file = directory.resolve("settings.json");
    Files.writeString(file, "{broken");
    assertEquals(defaults, new SettingsStore(directory).load(defaults));
    assertEquals("{broken", Files.readString(file));
    Files.writeString(file, "{\"canvas\":{\"width\":-1}}");
    assertEquals(defaults, new SettingsStore(directory).load(defaults));
  }

  @Test
  void futureVersionCannotBeOverwritten() throws Exception {
    Path file = directory.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":99}");
    var store = new SettingsStore(directory);
    assertEquals(defaults, store.load(defaults));
    assertThrows(java.io.IOException.class, () -> store.save(defaults));
    assertTrue(Files.readString(file).contains("99"));
  }

  @Test
  void persistenceFailureDoesNotChangeEffectiveSettings() throws Exception {
    Path blocked = Files.writeString(directory.resolve("not-a-directory"), "x");
    try (var jobs = new BackgroundJobs(2, 2)) {
      var manager = new SettingsManager(new SettingsStore(blocked), defaults, jobs, Runnable::run);
      var light =
          new UserSettings(
              1,
              new UserSettings.Appearance("light", true, false),
              defaults.canvas(),
              defaults.performance());
      assertThrows(ExecutionException.class, () -> manager.apply(light).get(3, TimeUnit.SECONDS));
      assertEquals(defaults, manager.current());
    }
  }

  @Test
  void savesRoundTripAndKeepBackup() throws Exception {
    var store = new SettingsStore(directory);
    store.save(defaults);
    store.save(defaults);
    assertEquals(defaults, store.load(defaults));
    assertTrue(Files.exists(directory.resolve("settings.json.bak")));
  }
}
