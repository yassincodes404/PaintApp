package com.paintstudio.editor.history;

import com.paintstudio.document.model.DrawingDocument;

public interface Command {
  void execute(DrawingDocument document);

  void undo(DrawingDocument document);

  long estimatedBytes();

  String name();
}
