package com.paintstudio.editor.history;

import com.paintstudio.common.logging.*;
import com.paintstudio.document.model.DrawingDocument;
import java.util.*;

public final class CommandManager {
  private record Entry(Command command, long before, long after) {}

  private final DrawingDocument document;
  private final Deque<Entry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private long bytes, limit;
  private int count;

  public CommandManager(DrawingDocument document, int mib, int count) {
    this.document = document;
    configure(mib, count);
  }

  public void configure(int mib, int count) {
    if (mib < 1 || mib > 256 || count < 1 || count > 10000)
      throw new IllegalArgumentException("Invalid history budget");
    limit = mib * 1024L * 1024;
    this.count = count;
    trim();
  }

  public void execute(Command command) {
    if (command.estimatedBytes() > limit)
      throw new IllegalArgumentException(
          "This edit exceeds the undo budget; increase it in Settings");
    long before = document.revision();
    command.execute(document);
    for (Entry discarded : redo) bytes -= discarded.command().estimatedBytes();
    redo.clear();
    undo.addLast(new Entry(command, before, document.revision()));
    bytes += command.estimatedBytes();
    trim();
    log(command.name());
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public long estimatedBytes() {
    return bytes;
  }

  public void undo() {
    if (canUndo()) {
      Entry e = undo.getLast();
      e.command().undo(document);
      document.restoreRevision(e.before());
      undo.removeLast();
      redo.addLast(e);
      log("undo");
    }
  }

  public void redo() {
    if (canRedo()) {
      Entry e = redo.getLast();
      e.command().execute(document);
      document.restoreRevision(e.after());
      redo.removeLast();
      undo.addLast(e);
      log("redo");
    }
  }

  private void trim() {
    int removed = 0;
    while (bytes > limit || undo.size() + redo.size() > count) {
      Entry e = !undo.isEmpty() ? undo.removeFirst() : redo.removeFirst();
      bytes -= e.command().estimatedBytes();
      removed++;
    }
    if (removed > 0)
      AppLogger.getInstance()
          .info(
              LogCategory.PERFORMANCE,
              CommandManager.class,
              "history.evicted",
              Map.of("commands", removed, "estimatedBytes", bytes));
  }

  private void log(String action) {
    AppLogger.getInstance()
        .info(
            LogCategory.ACTION,
            CommandManager.class,
            "document.edit",
            Map.of(
                "action",
                action,
                "documentId",
                document.id(),
                "revision",
                document.revision(),
                "historyBytes",
                bytes));
  }
}
