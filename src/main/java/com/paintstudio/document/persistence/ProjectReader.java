package com.paintstudio.document.persistence;

import com.google.gson.Strictness;
import com.google.gson.stream.*;
import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.document.model.*;
import com.paintstudio.drawing.geometry.Bounds;
import com.paintstudio.drawing.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Streaming parsing checks counts before allocating large point/object collections. */
public final class ProjectReader {
  private final EditorConfig policy;
  private int objects, points;

  public ProjectReader(EditorConfig policy) {
    this.policy = policy;
  }

  public DocumentSnapshot read(Path path) throws IOException {
    if (Files.size(path) > policy.maxFileMiB() * 1024L * 1024)
      throw new IOException("Project exceeds file limit");
    objects = 0;
    points = 0;
    try (var input =
            new com.paintstudio.common.io.LimitedInputStream(
                Files.newInputStream(path), policy.maxFileMiB() * 1024L * 1024);
        var source = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8);
        var r = new JsonReader(source)) {
      r.setStrictness(Strictness.STRICT);
      int version = 0;
      String id = null, background = null;
      long revision = 0;
      double[] bounds = null;
      List<Layer> layers = null;
      Set<String> keys = new HashSet<>();
      r.beginObject();
      while (r.hasNext()) {
        String key = unique(r, keys);
        switch (key) {
          case "formatVersion" -> version = r.nextInt();
          case "id" -> id = text(r);
          case "revision" -> revision = r.nextLong();
          case "background" -> background = text(r);
          case "bounds" -> bounds = array(r, 4);
          case "layers" -> {
            layers = new ArrayList<>();
            r.beginArray();
            while (r.hasNext()) {
              if (layers.size() >= 256) throw new IOException("Too many layers");
              layers.add(layer(r));
            }
            r.endArray();
          }
          default -> throw new IOException("Unknown project field: " + key);
        }
      }
      r.endObject();
      if (r.peek() != JsonToken.END_DOCUMENT
          || (version != 1 && version != 2 && version != 3)
          || bounds == null
          || bounds.length != 4) throw new IOException("Unsupported or incomplete project");
      var snapshot =
          new DocumentSnapshot(
              id,
              revision,
              new CanvasSettings(
                  new Bounds(bounds[0], bounds[1], bounds[2], bounds[3]), background),
              layers);
      // Apply the same model invariants used for interactive edits.
      return new DrawingDocument(snapshot, policy).snapshot();
    } catch (RuntimeException error) {
      throw new IOException("Invalid project: " + error.getMessage(), error);
    }
  }

  private Layer layer(JsonReader r) throws IOException {
    String id = null, name = null;
    boolean visible = true;
    List<DrawingObject> content = null;
    Set<String> keys = new HashSet<>();
    r.beginObject();
    while (r.hasNext())
      switch (unique(r, keys)) {
        case "id" -> id = text(r);
        case "name" -> name = text(r);
        case "visible" -> visible = r.nextBoolean();
        case "objects" -> {
          content = new ArrayList<>();
          r.beginArray();
          while (r.hasNext()) {
            if (Thread.currentThread().isInterrupted())
              throw new InterruptedIOException("Open cancelled");
            if (++objects > policy.maxObjects()) throw new IOException("Object limit exceeded");
            content.add(object(r));
          }
          r.endArray();
        }
        default -> throw new IOException("Unknown layer field");
      }
    r.endObject();
    return new Layer(id, name, visible, content);
  }

  private DrawingObject object(JsonReader r) throws IOException {
    String id = null, type = null;
    Integer color = null;
    double width = 0;
    double[] coords = null, geometry = null, widths = null, nibs = null;
    double aspect = 1;
    Set<String> keys = new HashSet<>();
    r.beginObject();
    while (r.hasNext())
      switch (unique(r, keys)) {
        case "id" -> id = text(r);
        case "type" -> type = text(r);
        case "color" -> color = r.nextInt();
        case "thickness" -> width = r.nextDouble();
        case "points" ->
            coords = array(r, Math.min(policy.maxStrokePoints(), policy.maxPoints() - points) * 2);
        case "widths" ->
            widths = array(r, Math.min(policy.maxStrokePoints(), policy.maxPoints() - points));
        case "nibs" ->
            nibs = array(r, Math.min(policy.maxStrokePoints(), policy.maxPoints() - points) * 2);
        case "nibAspect" -> aspect = r.nextDouble();
        case "geometry" -> geometry = array(r, 4);
        default -> throw new IOException("Unknown object field");
      }
    r.endObject();
    if (color == null || type == null) throw new IOException("Incomplete drawing object");
    if (type.equals("STROKE")) {
      if (coords == null || geometry != null) throw new IOException("Invalid stroke payload");
      points += coords.length / 2;
      return new StrokeObject(id, coords, color, width, widths, aspect, nibs);
    }
    if (geometry == null
        || geometry.length != 4
        || coords != null
        || widths != null
        || nibs != null
        || aspect != 1) throw new IOException("Invalid shape payload");
    return new ShapeObject(
        id,
        ShapeType.valueOf(type),
        geometry[0],
        geometry[1],
        geometry[2],
        geometry[3],
        color,
        width);
  }

  private static String unique(JsonReader r, Set<String> keys) throws IOException {
    String key = r.nextName();
    if (!keys.add(key)) throw new IOException("Duplicate field");
    return key;
  }

  private static String text(JsonReader r) throws IOException {
    String value = r.nextString();
    if (value.length() > 128) throw new IOException("Text field too long");
    return value;
  }

  private static double[] array(JsonReader r, int max) throws IOException {
    double[] values = new double[Math.min(64, max)];
    int size = 0;
    r.beginArray();
    while (r.hasNext()) {
      if (size >= max) throw new IOException("Array limit exceeded");
      if (size == values.length)
        values = Arrays.copyOf(values, Math.min(max, Math.max(1, size * 2)));
      double value = r.nextDouble();
      if (!Double.isFinite(value)) throw new IOException("Nonfinite coordinate");
      values[size++] = value;
    }
    r.endArray();
    return Arrays.copyOf(values, size);
  }
}
