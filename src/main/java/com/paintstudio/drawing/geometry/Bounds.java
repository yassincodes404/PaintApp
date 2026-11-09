package com.paintstudio.drawing.geometry;

public record Bounds(double minX, double minY, double maxX, double maxY) {
  public Bounds {
    if (!Double.isFinite(minX)
        || !Double.isFinite(minY)
        || !Double.isFinite(maxX)
        || !Double.isFinite(maxY)
        || maxX < minX
        || maxY < minY) throw new IllegalArgumentException("Invalid bounds");
  }

  public double width() {
    return maxX - minX;
  }

  public double height() {
    return maxY - minY;
  }

  public boolean intersects(Bounds b) {
    return maxX >= b.minX && minX <= b.maxX && maxY >= b.minY && minY <= b.maxY;
  }

  public boolean contains(double x, double y) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY;
  }

  public Bounds pad(double p) {
    return new Bounds(minX - p, minY - p, maxX + p, maxY + p);
  }

  public Bounds union(Bounds b) {
    return new Bounds(
        Math.min(minX, b.minX),
        Math.min(minY, b.minY),
        Math.max(maxX, b.maxX),
        Math.max(maxY, b.maxY));
  }
}
