package com.paintstudio.drawing.geometry;

import com.paintstudio.drawing.model.StrokeObject;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/** One fill preserves uniform opacity within a translucent stroke. */
public final class InkOutline {
  private InkOutline() {}

  public static Path2D build(StrokeObject stroke) {
    Path2D.Double path = new Path2D.Double();
    Ellipse2D.Double nib = new Ellipse2D.Double();
    AffineTransform rotation = new AffineTransform();
    double[] start = new double[2], end = new double[2];
    for (int i = 0; i < stroke.pointCount(); i++) {
      double x = stroke.x(i), y = stroke.y(i), radius = stroke.widthAt(i) / 2;
      double minor = radius * stroke.aspectAt(i);
      nib.setFrame(-radius, -minor, 2 * radius, 2 * minor);
      rotation.setToTranslation(x, y);
      rotation.rotate(stroke.angleAt(i));
      path.append(nib.getPathIterator(rotation), false);
      if (i == 0) continue;
      double ax = stroke.x(i - 1), ay = stroke.y(i - 1);
      double dx = x - ax, dy = y - ay, length = Math.hypot(dx, dy);
      if (length == 0) continue;
      double nx = -dy / length, ny = dx / length;
      edge(stroke, i - 1, nx, ny, start);
      edge(stroke, i, nx, ny, end);
      path.moveTo(ax + start[0], ay + start[1]);
      path.lineTo(ax - start[0], ay - start[1]);
      path.lineTo(x - end[0], y - end[1]);
      path.lineTo(x + end[0], y + end[1]);
      path.closePath();
    }
    return path;
  }

  private static void edge(StrokeObject stroke, int index, double nx, double ny, double[] offset) {
    double angle = stroke.angleAt(index);
    double cos = Math.cos(angle), sin = Math.sin(angle);
    double aspect = stroke.aspectAt(index);
    double along = nx * cos + ny * sin;
    double across = (-nx * sin + ny * cos) * aspect;
    double scale = stroke.widthAt(index) / (2 * Math.hypot(along, across));
    offset[0] = scale * (along * cos - across * aspect * sin);
    offset[1] = scale * (along * sin + across * aspect * cos);
  }
}
