package com.paintstudio.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentProjectsStoreTest {
  @TempDir Path directory;

  @Test
  void persistsMostRecentFirstWithDeduplicationAndLimit() throws Exception {
    Path first = directory.resolve("first.pspaint");
    Path second = directory.resolve("second.pspaint");
    Path third = directory.resolve("third.pspaint");
    var store = new RecentProjectsStore(directory, 2);
    store.remember(first);
    store.remember(second);
    store.remember(first);
    store.remember(third);
    var reopened = new RecentProjectsStore(directory, 2);
    assertEquals(
        java.util.List.of(third.toString(), first.toString()),
        reopened.load().stream().map(RecentProjectsStore.Entry::path).toList());
    reopened.clear();
    assertTrue(new RecentProjectsStore(directory, 2).load().isEmpty());
  }

  @Test
  void doesNotOverwriteFutureVersion() throws Exception {
    Path file = directory.resolve("recent-projects.json");
    String future = "{\"schemaVersion\":2,\"entries\":[]}";
    Files.writeString(file, future);
    var store = new RecentProjectsStore(directory, 12);
    assertThrows(
        java.io.IOException.class, () -> store.remember(directory.resolve("drawing.pspaint")));
    assertThrows(java.io.IOException.class, store::clear);
    assertEquals(future, Files.readString(file));
  }
}
