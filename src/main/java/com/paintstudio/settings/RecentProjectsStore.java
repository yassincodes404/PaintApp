package com.paintstudio.settings;

import com.google.gson.*;
import com.paintstudio.common.io.AtomicFiles;
import com.paintstudio.common.logging.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded metadata; only successful explicit open/save operations add paths. */
public final class RecentProjectsStore {
  public record Entry(String path, String openedAt) {}

  private record State(int schemaVersion, List<Entry> entries) {}

  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final Path file;
  private final int limit;
  private boolean readOnly;

  public RecentProjectsStore(Path directory, int limit) {
    this.file = directory.resolve("recent-projects.json");
    this.limit = limit;
  }

  public List<Entry> load() {
    if (!Files.exists(file)) return List.of();
    try {
      if (Files.size(file) > 65536) throw new IOException("Recent projects file is too large");
      State state = JSON.fromJson(Files.readString(file), State.class);
      if (state == null || state.schemaVersion() != 1) {
        readOnly = true;
        throw new IOException("Unsupported recent-project schema");
      }
      if (state.entries() == null || state.entries().size() > 30)
        throw new IOException("Invalid recent-project list");
      LinkedHashMap<Path, Entry> unique = new LinkedHashMap<>();
      for (var entry : state.entries()) {
        if (entry == null || entry.path() == null || entry.path().length() > 4096)
          throw new IOException("Invalid recent path");
        Path path = Path.of(entry.path()).normalize();
        if (!path.isAbsolute() || path.getParent() == null)
          throw new IOException("Expected absolute project path");
        Instant.parse(entry.openedAt());
        unique.putIfAbsent(path.normalize(), entry);
      }
      return unique.values().stream().limit(limit).toList();
    } catch (IOException | RuntimeException error) {
      AppLogger.getInstance()
          .warn(
              LogCategory.CONFIGURATION,
              RecentProjectsStore.class,
              "recent.load.failed",
              Map.of("reason", String.valueOf(error.getMessage())));
      return List.of();
    }
  }

  public List<Entry> remember(Path path) throws IOException {
    var entries = new ArrayList<>(load());
    Path normalized = path.toAbsolutePath().normalize();
    entries.removeIf(entry -> Path.of(entry.path()).normalize().equals(normalized));
    entries.addFirst(new Entry(normalized.toString(), Instant.now().toString()));
    List<Entry> result = List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
    write(result);
    return result;
  }

  public void clear() throws IOException {
    if (!Files.exists(file)) return;
    load();
    write(List.of());
  }

  private void write(List<Entry> entries) throws IOException {
    if (readOnly) throw new IOException("Recent projects were created by a newer application");
    byte[] encoded = JSON.toJson(new State(1, entries)).getBytes(StandardCharsets.UTF_8);
    if (encoded.length > 65536)
      throw new IOException("Recent projects metadata exceeds size limit");
    AtomicFiles.write(file, out -> out.write(encoded));
  }
}
