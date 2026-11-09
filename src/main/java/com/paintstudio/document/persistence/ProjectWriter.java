package com.paintstudio.document.persistence;

import com.google.gson.stream.JsonWriter;
import com.paintstudio.common.io.AtomicFiles;
import com.paintstudio.document.model.*;
import com.paintstudio.drawing.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class ProjectWriter {
  public void write(Path path, DocumentSnapshot document, long maxBytes) throws IOException {
    AtomicFiles.write(
        path,
        stream -> {
          OutputStream bounded =
              new FilterOutputStream(stream) {
                long count;

                @Override
                public void write(int b) throws IOException {
                  if (++count > maxBytes) throw new IOException("Project exceeds file limit");
                  out.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                  if ((count += len) > maxBytes)
                    throw new IOException("Project exceeds file limit");
                  out.write(b, off, len);
                }
              };
          JsonWriter w = new JsonWriter(new OutputStreamWriter(bounded, StandardCharsets.UTF_8));
          w.beginObject()
              .name("formatVersion")
              .value(
                  document.layers().stream()
                      .flatMap(layer -> layer.objects().stream())
                      .filter(StrokeObject.class::isInstance)
                      .map(StrokeObject.class::cast)
                      .mapToInt(
                          stroke -> stroke.hasOrientedNib() ? 3 : stroke.hasVariableNib() ? 2 : 1)
                      .max()
                      .orElse(1))
              .name("id")
              .value(document.id())
              .name("revision")
              .value(document.revision())
              .name("background")
              .value(document.canvas().background());
          var b = document.canvas().bounds();
          w.name("bounds")
              .beginArray()
              .value(b.minX())
              .value(b.minY())
              .value(b.maxX())
              .value(b.maxY())
              .endArray();
          w.name("layers").beginArray();
          for (var layer : document.layers()) {
            w.beginObject()
                .name("id")
                .value(layer.id())
                .name("name")
                .value(layer.name())
                .name("visible")
                .value(layer.visible())
                .name("objects")
                .beginArray();
            for (var object : layer.objects()) {
              if (Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("Save cancelled");
              w.beginObject()
                  .name("id")
                  .value(object.id())
                  .name("color")
                  .value(object.argb())
                  .name("thickness")
                  .value(object.thickness());
              if (object instanceof StrokeObject stroke) {
                w.name("type").value("STROKE").name("points").beginArray();
                for (int i = 0; i < stroke.pointCount(); i++)
                  w.value(stroke.x(i)).value(stroke.y(i));
                w.endArray();
                if (stroke.hasVariableNib()) {
                  w.name("nibAspect").value(stroke.nibAspect()).name("widths").beginArray();
                  for (int i = 0; i < stroke.pointCount(); i++) w.value(stroke.widthAt(i));
                  w.endArray();
                }
                if (stroke.hasOrientedNib()) {
                  w.name("nibs").beginArray();
                  for (int i = 0; i < stroke.pointCount(); i++)
                    w.value(stroke.aspectAt(i)).value(stroke.angleAt(i));
                  w.endArray();
                }
              } else if (object instanceof ShapeObject shape) {
                w.name("type")
                    .value(shape.type().name())
                    .name("geometry")
                    .beginArray()
                    .value(shape.x())
                    .value(shape.y())
                    .value(shape.width())
                    .value(shape.height())
                    .endArray();
              }
              w.endObject();
            }
            w.endArray().endObject();
          }
          w.endArray().endObject();
          w.flush();
        });
  }
}
