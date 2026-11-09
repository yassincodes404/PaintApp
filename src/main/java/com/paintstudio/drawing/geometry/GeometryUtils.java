package com.paintstudio.drawing.geometry;

public final class GeometryUtils {
  private GeometryUtils() {}

  public static double segmentDistance(
      double x, double y, double ax, double ay, double bx, double by) {
    double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
    double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length));
    return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
  }
}
