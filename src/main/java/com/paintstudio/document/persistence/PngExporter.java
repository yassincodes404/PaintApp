package com.paintstudio.document.persistence;

import com.paintstudio.common.io.AtomicFiles;
import com.paintstudio.document.model.*;
import com.paintstudio.drawing.geometry.Bounds;
import com.paintstudio.drawing.model.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** CPU export from an immutable model; no JavaFX snapshot or UI-thread pixel copying. */
public final class PngExporter {
  public void export(Path path, DocumentSnapshot snapshot, Bounds bounds, long maxPixels)
      throws IOException {
    int width = (int) Math.ceil(bounds.width()), height = (int) Math.ceil(bounds.height());
    if (width <= 0 || height <= 0 || (long) width * height > maxPixels)
      throw new IOException("Export exceeds pixel budget; use artwork bounds or a smaller area");
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      String bg = snapshot.canvas().background();
      int rgb = Integer.parseInt(bg.substring(1, 7), 16),
          alpha = bg.length() == 9 ? Integer.parseInt(bg.substring(7), 16) : 255;
      g.setColor(new Color((alpha << 24) | rgb, true));
      g.fillRect(0, 0, width, height);
      g.translate(-bounds.minX(), -bounds.minY());
      for (var layer : snapshot.layers())
        if (layer.visible())
          for (var object : layer.objects()) {
            if (Thread.currentThread().isInterrupted())
              throw new InterruptedIOException("Export cancelled");
            if (!bounds.intersects(object.bounds())) continue;
            g.setColor(new Color(object.argb(), true));
            g.setStroke(
                new BasicStroke(
                    (float) object.thickness(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (object instanceof StrokeObject stroke) {
              if (stroke.hasVariableNib()) {
                g.fill(com.paintstudio.drawing.geometry.InkOutline.build(stroke));
              } else if (stroke.pointCount() == 1) {
                double r = stroke.thickness() / 2;
                g.fill(new Ellipse2D.Double(stroke.x(0) - r, stroke.y(0) - r, r * 2, r * 2));
              } else {
                Path2D p = new Path2D.Double();
                p.moveTo(stroke.x(0), stroke.y(0));
                for (int i = 1; i < stroke.pointCount(); i++) p.lineTo(stroke.x(i), stroke.y(i));
                g.draw(p);
              }
            } else if (object instanceof ShapeObject s) {
              g.draw(
                  s.type() == ShapeType.RECTANGLE
                      ? new Rectangle2D.Double(s.x(), s.y(), s.width(), s.height())
                      : new Ellipse2D.Double(s.x(), s.y(), s.width(), s.height()));
            }
          }
      AtomicFiles.write(
          path,
          out -> {
            if (!ImageIO.write(image, "png", out)) throw new IOException("PNG encoder unavailable");
          });
    } finally {
      g.dispose();
      image.flush();
    }
  }
}
