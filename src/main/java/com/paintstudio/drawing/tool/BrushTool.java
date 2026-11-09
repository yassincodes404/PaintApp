package com.paintstudio.drawing.tool;

import com.paintstudio.drawing.model.*;
import com.paintstudio.editor.interaction.InputSample;
import java.util.*;

public final class BrushTool implements DrawingTool {
  private double[] points = new double[128];
  private double[] widths = new double[64];
  private double[] nibs;
  private int count;
  private boolean variable;
  private double filteredPressure;
  private final InkPreset preset;
  private final String id = UUID.randomUUID().toString();
  private final int color, max;
  private final double width, spacing;

  public BrushTool(
      InputSample start, int color, double width, int max, double spacing, InkPreset preset) {
    this.preset = preset;
    int alpha = (int) Math.round((color >>> 24) * preset.opacity);
    this.color = (color & 0xffffff) | (alpha << 24);
    filteredPressure = start.pressure();
    this.width = width;
    this.max = max;
    this.spacing = spacing;
    add(start);
  }

  private void add(InputSample s) {
    if (count >= max)
      throw new IllegalArgumentException(
          "Stroke point limit reached; release to start another stroke");
    if (count * 2 == points.length)
      points = Arrays.copyOf(points, Math.min(max * 2, points.length * 2));
    if (count == widths.length) widths = Arrays.copyOf(widths, Math.min(max, widths.length * 2));
    double blend = count == 0 ? 0 : preset.smoothing;
    points[count * 2] = count == 0 ? s.x() : s.x() * (1 - blend) + points[(count - 1) * 2] * blend;
    points[count * 2 + 1] =
        count == 0 ? s.y() : s.y() * (1 - blend) + points[(count - 1) * 2 + 1] * blend;
    filteredPressure = filteredPressure * .25 + s.pressure() * .75;
    widths[count] = width * (preset.minimum + (1 - preset.minimum) * Math.sqrt(filteredPressure));
    variable |= widths[count] != width;
    double lean = s.lean();
    if (nibs != null || lean > .02 || (preset.aspect < 1 && Double.isFinite(s.rotation()))) {
      if (nibs == null) {
        nibs = new double[points.length];
        for (int i = 0; i < count; i++) nibs[i * 2] = preset.aspect;
      } else if (nibs.length < points.length) {
        nibs = Arrays.copyOf(nibs, points.length);
      }
      nibs[count * 2] = aspect(s);
      nibs[count * 2 + 1] = s.nibAngle();
    }
    count++;
  }

  public void drag(InputSample s) {
    if (Math.hypot(s.x() - points[(count - 1) * 2], s.y() - points[(count - 1) * 2 + 1]) >= spacing
        || Math.abs(s.pressure() - filteredPressure) >= .04
        || Math.abs(aspect(s) - (nibs == null ? preset.aspect : nibs[(count - 1) * 2])) > .03
        || (nibs != null
            && Math.abs(Math.IEEEremainder(s.nibAngle() - nibs[(count - 1) * 2 + 1], Math.TAU))
                > .16)) add(s);
  }

  private double aspect(InputSample sample) {
    return Math.max(.2, preset.aspect * (1 - .55 * sample.lean()));
  }

  public DrawingObject preview() {
    return new StrokeObject(
        id,
        Arrays.copyOf(points, count * 2),
        color,
        width,
        variable ? Arrays.copyOf(widths, count) : null,
        preset.aspect,
        nibs == null ? null : Arrays.copyOf(nibs, count * 2));
  }
}
