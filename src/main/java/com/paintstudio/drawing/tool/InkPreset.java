package com.paintstudio.drawing.tool;

/** The nib geometry and response curves used by each preset. */
public enum InkPreset {
  PEN("Pen", "#F2F0ED", 4, .35, 1, 1, .28),
  BALLPOINT("Ballpoint", "#FF4E65", 3, .60, 1, .95, .20),
  FOUNTAIN("Fountain pen", "#71C7FF", 5, .16, .82, 1, .27),
  BRUSH("Brush pen", "#75D7A0", 11, .10, .75, 1, .25),
  PENCIL("Pencil", "#B6BBC5", 3, .35, .92, .68, .14),
  HIGHLIGHTER("Highlighter", "#F6DD4A", 19, .72, .44, .36, .16),
  CALLIGRAPHY("Calligraphy", "#BA91F3", 10, .28, .38, 1, .22);

  public final String label;
  public final String color;
  public final int width;
  public final double minimum, aspect, opacity, smoothing;

  InkPreset(
      String label,
      String color,
      int width,
      double minimum,
      double aspect,
      double opacity,
      double smoothing) {
    this.label = label;
    this.color = color;
    this.width = width;
    this.minimum = minimum;
    this.aspect = aspect;
    this.opacity = opacity;
    this.smoothing = smoothing;
  }
}
