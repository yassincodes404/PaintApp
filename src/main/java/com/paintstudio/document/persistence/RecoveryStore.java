package com.paintstudio.document.persistence;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.document.model.DocumentSnapshot;
import com.paintstudio.settings.UserSettings;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RecoveryStore {
  private final Path directory;
  private final EditorConfig policy;

  public RecoveryStore(Path directory, EditorConfig policy) {
    this.directory = directory;
    this.policy = policy;
  }

  public List<Path> candidates() throws IOException {
    if (!Files.isDirectory(directory)) return List.of();
    try (var paths = Files.list(directory)) {
      return paths
          .filter(p -> p.getFileName().toString().endsWith(".pspaint"))
          .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
          .toList();
    }
  }

  public void save(DocumentSnapshot snapshot, UserSettings.Performance settings)
      throws IOException {
    Files.createDirectories(directory);
    Path path =
        directory.resolve(
            String.format("%019d", System.currentTimeMillis())
                + "-"
                + token(snapshot.id())
                + "-"
                + UUID.randomUUID()
                + ".pspaint");
    long max = Math.min(policy.maxFileMiB(), settings.recoveryMiB()) * 1024L * 1024;
    new ProjectWriter().write(path, snapshot, max);
    long bytes = 0;
    int kept = 0;
    for (Path candidate : candidates()) {
      long size = Files.size(candidate);
      if (++kept > settings.recoveryGenerations()
          || bytes + size > settings.recoveryMiB() * 1024L * 1024) Files.delete(candidate);
      else bytes += size;
    }
  }

  private static String token(String id) {
    return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  }

  public void clearDocument(String id) throws IOException {
    String marker = "-" + token(id) + "-";
    for (Path candidate : candidates())
      if (candidate.getFileName().toString().contains(marker)) Files.deleteIfExists(candidate);
  }

  public void clear() throws IOException {
    for (Path candidate : candidates()) Files.deleteIfExists(candidate);
  }
}
