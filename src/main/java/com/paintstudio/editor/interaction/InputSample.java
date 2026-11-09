package com.paintstudio.editor.interaction;

/** Document coordinates and normalized pen data. Missing angles use NaN. */
public record InputSample(
    double x, double y, double pressure, double tiltX, double tiltY, double rotation) {
  public InputSample(double x, double y, double pressure) {
    this(x, y, pressure, Double.NaN, Double.NaN, Double.NaN);
  }

  public InputSample {
    pressure = Double.isFinite(pressure) ? Math.clamp(pressure, 0, 1) : 1;
  }

  public InputSample at(double newX, double newY) {
    return new InputSample(newX, newY, pressure, tiltX, tiltY, rotation);
  }

  public double lean() {
    if (!Double.isFinite(tiltX) && !Double.isFinite(tiltY)) return 0;
    return Math.min(1, Math.hypot(component(tiltX), component(tiltY)) / 90);
  }

  public double nibAngle() {
    if (Double.isFinite(rotation)) return Math.IEEEremainder(Math.toRadians(rotation), Math.TAU);
    return Math.atan2(component(tiltY), component(tiltX));
  }

  private static double component(double value) {
    return Double.isFinite(value) ? Math.clamp(value, -90, 90) : 0;
  }
}
