package com.paintstudio.document.model;

import com.paintstudio.drawing.model.DrawingObject;
import java.util.*;

public record Layer(String id, String name, boolean visible, List<DrawingObject> objects) {
  public Layer {
    if (id == null || id.isBlank() || id.length() > 128 || name == null || name.length() > 128)
      throw new IllegalArgumentException("Invalid layer");
    objects = List.copyOf(objects);
  }
}
