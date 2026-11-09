package com.paintstudio.document.model;

import java.util.*;

public record DocumentSnapshot(
    String id, long revision, CanvasSettings canvas, List<Layer> layers) {
  public DocumentSnapshot {
    if (id == null
        || id.isBlank()
        || id.length() > 128
        || revision < 0
        || canvas == null
        || layers == null
        || layers.isEmpty()
        || layers.size() > 256) throw new IllegalArgumentException("Invalid document");
    layers = List.copyOf(layers);
  }
}
