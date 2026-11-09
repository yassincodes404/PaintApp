package com.paintstudio.document.model;

import com.paintstudio.drawing.geometry.Bounds;

public record CanvasSettings(Bounds bounds, String background) {
  public CanvasSettings {
    if (bounds == null
        || bounds.width() <= 0
        || bounds.height() <= 0
        || background == null
        || !background.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
      throw new IllegalArgumentException("Invalid canvas settings");
  }
}
