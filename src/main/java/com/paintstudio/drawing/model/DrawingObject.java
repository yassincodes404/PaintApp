package com.paintstudio.drawing.model;

import com.paintstudio.drawing.geometry.Bounds;

public sealed interface DrawingObject permits StrokeObject, ShapeObject {
  String id();

  int argb();

  double thickness();

  Bounds bounds();

  boolean hit(double x, double y, double tolerance);

  DrawingObject transform(
      double dx, double dy, double sx, double sy, double originX, double originY);

  DrawingObject recolor(int argb);

  long estimatedBytes();

  default int pointCount() {
    return 0;
  }
}
