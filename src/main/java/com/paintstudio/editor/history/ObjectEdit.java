package com.paintstudio.editor.history;

import com.paintstudio.document.model.DrawingDocument;
import com.paintstudio.drawing.geometry.Bounds;
import com.paintstudio.drawing.model.DrawingObject;

public record ObjectEdit(
    String name,
    int layer,
    int index,
    DrawingObject before,
    DrawingObject after,
    Bounds oldBounds,
    Bounds newBounds)
    implements Command {
  public void execute(DrawingDocument d) {
    d.replace(layer, index, before, after, newBounds);
  }

  public void undo(DrawingDocument d) {
    d.replace(layer, index, after, before, oldBounds);
  }

  public long estimatedBytes() {
    return 192
        + (before == null ? 0 : before.estimatedBytes())
        + (after == null ? 0 : after.estimatedBytes());
  }
}
