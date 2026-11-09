package com.paintstudio.common.io;

import static java.nio.file.StandardCopyOption.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Same-directory replacement. A backup is retained when atomic moves are unavailable. */
public final class AtomicFiles {
  private AtomicFiles() {}

  @FunctionalInterface
  public interface Writer {
    void write(OutputStream stream) throws IOException;
  }

  public static void write(Path destination, Writer writer) throws IOException {
    destination = destination.toAbsolutePath();
    Files.createDirectories(destination.getParent());
    Path temporary = Files.createTempFile(destination.getParent(), ".paintstudio-", ".tmp");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        writer.write(output);
      }
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        if (Files.exists(destination))
          Files.copy(
              destination,
              destination.resolveSibling(destination.getFileName() + ".bak"),
              REPLACE_EXISTING);
        Files.move(temporary, destination, REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
