package com.paintstudio.drawing.model;

import com.paintstudio.drawing.geometry.*;

/** Immutable primitive point storage, shared safely by history and save snapshots. */
public final class StrokeObject implements DrawingObject {
  private final String id;
  private final double[] points;
  private final int argb;
  private final double thickness;
  private final Bounds bounds;
  private final double[] widths;
  private final double nibAspect;
  private final double[] nibs;

  public StrokeObject(String id, double[] points, int argb, double thickness) {
    this(id, points, argb, thickness, null, 1);
  }

  public StrokeObject(
      String id, double[] points, int argb, double thickness, double[] widths, double nibAspect) {
    this(id, points, argb, thickness, widths, nibAspect, null);
  }

  public StrokeObject(
      String id,
      double[] points,
      int argb,
      double thickness,
      double[] widths,
      double nibAspect,
      double[] nibs) {
    if (id == null
        || id.isBlank()
        || id.length() > 128
        || points.length < 2
        || points.length % 2 != 0
        || !Double.isFinite(thickness)
        || thickness <= 0
        || thickness > 512) throw new IllegalArgumentException("Invalid stroke");
    if (!Double.isFinite(nibAspect)
        || nibAspect < .2
        || nibAspect > 1
        || (widths != null && widths.length != points.length / 2))
      throw new IllegalArgumentException("Invalid nib geometry");
    if (nibs != null && nibs.length != points.length)
      throw new IllegalArgumentException("Invalid sample orientation count");
    this.nibs = nibs == null ? null : nibs.clone();
    for (int i = 0; this.nibs != null && i < this.nibs.length; i += 2) {
      double aspect = this.nibs[i], angle = this.nibs[i + 1];
      if (!Double.isFinite(aspect)
          || aspect < .2
          || aspect > 1
          || !Double.isFinite(angle)
          || Math.abs(angle) > Math.PI)
        throw new IllegalArgumentException("Invalid sample orientation");
    }
    this.widths = widths == null ? null : widths.clone();
    this.nibAspect = nibAspect;
    double maximumWidth = widths == null ? thickness : 0;
    if (this.widths != null) {
      for (double width : this.widths) {
        if (!Double.isFinite(width) || width <= 0 || width > 512)
          throw new IllegalArgumentException("Invalid sample width");
        maximumWidth = Math.max(maximumWidth, width);
      }
    }
    this.id = id;
    this.points = points.clone();
    this.argb = argb;
    this.thickness = thickness;
    double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
    for (int i = 0; i < points.length; i += 2) {
      if (!Double.isFinite(points[i]) || !Double.isFinite(points[i + 1]))
        throw new IllegalArgumentException("Nonfinite point");
      minX = Math.min(minX, points[i]);
      maxX = Math.max(maxX, points[i]);
      minY = Math.min(minY, points[i + 1]);
      maxY = Math.max(maxY, points[i + 1]);
    }
    bounds = new Bounds(minX, minY, maxX, maxY).pad(maximumWidth / 2);
  }

  public String id() {
    return id;
  }

  public int argb() {
    return argb;
  }

  public double thickness() {
    return thickness;
  }

  public double widthAt(int index) {
    return widths == null ? thickness : widths[index];
  }

  public boolean hasVariableNib() {
    return widths != null || nibAspect != 1 || nibs != null;
  }

  public double nibAspect() {
    return nibAspect;
  }

  public boolean hasOrientedNib() {
    return nibs != null;
  }

  public double aspectAt(int index) {
    return nibs == null ? nibAspect : nibs[2 * index];
  }

  public double angleAt(int index) {
    return nibs == null ? 0 : nibs[2 * index + 1];
  }

  public int pointCount() {
    return points.length / 2;
  }

  public double x(int i) {
    return points[i * 2];
  }

  public double y(int i) {
    return points[i * 2 + 1];
  }

  public Bounds bounds() {
    return bounds;
  }

  public long estimatedBytes() {
    return 176L
        + points.length * 8L
        + (widths == null ? 0 : widths.length * 8L)
        + (nibs == null ? 0 : nibs.length * 8L);
  }

  public boolean hit(double x, double y, double tolerance) {
    if (!bounds.pad(tolerance).contains(x, y)) return false;
    if (hasVariableNib()) {
      var outline = InkOutline.build(this);
      return outline.contains(x, y)
          || outline.intersects(x - tolerance, y - tolerance, 2 * tolerance, 2 * tolerance);
    }
    if (pointCount() == 1) return Math.hypot(x - x(0), y - y(0)) <= thickness / 2 + tolerance;
    for (int i = 1; i < pointCount(); i++)
      if (GeometryUtils.segmentDistance(x, y, x(i - 1), y(i - 1), x(i), y(i))
          <= thickness / 2 + tolerance) return true;
    return false;
  }

  public DrawingObject transform(double dx, double dy, double sx, double sy, double ox, double oy) {
    double[] moved = new double[points.length];
    for (int i = 0; i < pointCount(); i++) {
      moved[2 * i] = ox + (x(i) - ox) * sx + dx;
      moved[2 * i + 1] = oy + (y(i) - oy) * sy + dy;
    }
    return new StrokeObject(id, moved, argb, thickness, widths, nibAspect, nibs);
  }

  public DrawingObject recolor(int color) {
    return new StrokeObject(
        id, points, (argb & 0xff000000) | (color & 0xffffff), thickness, widths, nibAspect, nibs);
  }
}
