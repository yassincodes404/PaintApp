package com.paintstudio.editor.history;

import com.paintstudio.document.model.*;
import java.util.List;

public record LayerEdit(String name, List<Layer> before, List<Layer> after) implements Command {
  public LayerEdit {
    before = List.copyOf(before);
    after = List.copyOf(after);
  }

  public void execute(DrawingDocument d) {
    d.setLayers(after);
  }

  public void undo(DrawingDocument d) {
    d.setLayers(before);
  }

  public long estimatedBytes() {
    long bytes = 192;
    for (var list : List.of(before, after))
      for (var layer : list) {
        bytes += 128 + 8L * layer.objects().size();
        // Conservative: shared payloads count against budget as well.
        for (var object : layer.objects()) bytes += object.estimatedBytes();
      }
    return bytes;
  }
}
