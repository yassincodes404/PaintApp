package com.paintstudio.ui.viewport;

import com.paintstudio.drawing.geometry.Bounds;

/** Pan is the document coordinate at the viewport origin. */
public final class ViewportTransform {
  private double x, y, zoom = 1;

  public double x() {
    return x;
  }

  public double y() {
    return y;
  }

  public double zoom() {
    return zoom;
  }

  public double documentX(double screen) {
    return screen / zoom + x;
  }

  public double documentY(double screen) {
    return screen / zoom + y;
  }

  public void center(Bounds bounds, double width, double height) {
    x = (bounds.minX() + bounds.maxX()) / 2 - width / (2 * zoom);
    y = (bounds.minY() + bounds.maxY()) / 2 - height / (2 * zoom);
  }

  public void pan(double dx, double dy) {
    x -= dx / zoom;
    y -= dy / zoom;
  }

  public void zoomAt(double factor, double sx, double sy, double min, double max) {
    double dx = documentX(sx), dy = documentY(sy);
    zoom = Math.max(min, Math.min(max, zoom * factor));
    x = dx - sx / zoom;
    y = dy - sy / zoom;
  }

  public Bounds visible(double width, double height) {
    return new Bounds(x, y, x + width / zoom, y + height / zoom);
  }
}
