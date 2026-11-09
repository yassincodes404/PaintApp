package com.paintstudio.drawing.model;

import com.paintstudio.drawing.geometry.Bounds;

public record ShapeObject(
    String id,
    ShapeType type,
    double x,
    double y,
    double width,
    double height,
    int argb,
    double thickness)
    implements DrawingObject {
  public ShapeObject {
    if (id == null
        || id.isBlank()
        || id.length() > 128
        || type == null
        || !Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(width)
        || !Double.isFinite(height)
        || width < 0
        || height < 0
        || !Double.isFinite(thickness)
        || thickness <= 0
        || thickness > 512) throw new IllegalArgumentException("Invalid shape");
  }

  public Bounds bounds() {
    return new Bounds(x, y, x + width, y + height).pad(thickness / 2);
  }

  public long estimatedBytes() {
    return 192;
  }

  public boolean hit(double px, double py, double t) {
    t += thickness / 2;
    if (!bounds().pad(t).contains(px, py)) return false;
    if (type == ShapeType.RECTANGLE)
      return Math.min(
              Math.min(Math.abs(px - x), Math.abs(px - x - width)),
              Math.min(Math.abs(py - y), Math.abs(py - y - height)))
          <= t;
    double rx = Math.max(width / 2, .01), ry = Math.max(height / 2, .01);
    return Math.abs(Math.hypot((px - x - rx) / rx, (py - y - ry) / ry) - 1) <= t / Math.min(rx, ry);
  }

  public DrawingObject transform(double dx, double dy, double sx, double sy, double ox, double oy) {
    return new ShapeObject(
        id,
        type,
        ox + (x - ox) * sx + dx,
        oy + (y - oy) * sy + dy,
        width * sx,
        height * sy,
        argb,
        thickness);
  }

  public DrawingObject recolor(int color) {
    return new ShapeObject(id, type, x, y, width, height, color, thickness);
  }
}
