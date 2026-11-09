package com.paintstudio.drawing.renderer;

import com.paintstudio.drawing.model.*;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

public final class DrawingRenderer {
  private DrawingRenderer() {}

  public static Color color(int argb) {
    return Color.rgb(
        (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, ((argb >>> 24) & 255) / 255.0);
  }

  public static int argb(Color c) {
    return ((int) Math.round(c.getOpacity() * 255) << 24)
        | ((int) Math.round(c.getRed() * 255) << 16)
        | ((int) Math.round(c.getGreen() * 255) << 8)
        | (int) Math.round(c.getBlue() * 255);
  }

  public static void draw(GraphicsContext g, DrawingObject object) {
    g.setStroke(color(object.argb()));
    g.setFill(color(object.argb()));
    g.setLineWidth(object.thickness());
    g.setLineCap(StrokeLineCap.ROUND);
    g.setLineJoin(StrokeLineJoin.ROUND);
    if (object instanceof StrokeObject stroke) {
      if (stroke.hasVariableNib()) {
        var iterator =
            com.paintstudio.drawing.geometry.InkOutline.build(stroke).getPathIterator(null);
        double[] p = new double[6];
        g.beginPath();
        while (!iterator.isDone()) {
          switch (iterator.currentSegment(p)) {
            case java.awt.geom.PathIterator.SEG_MOVETO -> g.moveTo(p[0], p[1]);
            case java.awt.geom.PathIterator.SEG_LINETO -> g.lineTo(p[0], p[1]);
            case java.awt.geom.PathIterator.SEG_CUBICTO ->
                g.bezierCurveTo(p[0], p[1], p[2], p[3], p[4], p[5]);
            case java.awt.geom.PathIterator.SEG_CLOSE -> g.closePath();
            default -> throw new IllegalStateException("Unexpected ink path");
          }
          iterator.next();
        }
        g.fill();
      } else if (stroke.pointCount() == 1) {
        double r = stroke.thickness() / 2;
        g.fillOval(stroke.x(0) - r, stroke.y(0) - r, 2 * r, 2 * r);
      } else {
        g.beginPath();
        g.moveTo(stroke.x(0), stroke.y(0));
        for (int i = 1; i < stroke.pointCount(); i++) g.lineTo(stroke.x(i), stroke.y(i));
        g.stroke();
      }
    } else if (object instanceof ShapeObject shape) {
      if (shape.type() == ShapeType.RECTANGLE)
        g.strokeRect(shape.x(), shape.y(), shape.width(), shape.height());
      else g.strokeOval(shape.x(), shape.y(), shape.width(), shape.height());
    }
  }
}
