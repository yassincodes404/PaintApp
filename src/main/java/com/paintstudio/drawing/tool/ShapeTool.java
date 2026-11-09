package com.paintstudio.drawing.tool;

import com.paintstudio.drawing.model.*;
import com.paintstudio.editor.interaction.InputSample;
import java.util.UUID;

public final class ShapeTool implements DrawingTool {
  private final InputSample start;
  private InputSample end;
  private final ShapeType type;
  private final int color;
  private final double width;
  private final String id = UUID.randomUUID().toString();

  public ShapeTool(InputSample start, ShapeType type, int color, double width) {
    this.start = start;
    end = start;
    this.type = type;
    this.color = color;
    this.width = width;
  }

  public void drag(InputSample sample) {
    end = sample;
  }

  public DrawingObject preview() {
    return new ShapeObject(
        id,
        type,
        Math.min(start.x(), end.x()),
        Math.min(start.y(), end.y()),
        Math.abs(end.x() - start.x()),
        Math.abs(end.y() - start.y()),
        color,
        width);
  }
}
