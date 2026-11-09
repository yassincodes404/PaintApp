package com.paintstudio.document.model;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.drawing.geometry.Bounds;
import com.paintstudio.drawing.model.*;
import com.paintstudio.settings.UserSettings;
import java.util.*;

/** Single-owner mutable model. Immutable objects make snapshot capture inexpensive. */
public final class DrawingDocument {
  private final Thread owner = Thread.currentThread();
  private final EditorConfig policy;
  private final String id;
  private final List<MutableLayer> layers = new ArrayList<>();
  private final Set<String> objectIds = new HashSet<>();
  private CanvasSettings canvas;
  private long revision, savedRevision, revisionSequence;
  private int pointCount, objectCount;

  private static final class MutableLayer {
    final String id, name;
    final boolean visible;
    final ArrayList<DrawingObject> objects;

    MutableLayer(Layer layer) {
      id = layer.id();
      name = layer.name();
      visible = layer.visible();
      objects = new ArrayList<>(layer.objects());
    }

    Layer snapshot() {
      return new Layer(id, name, visible, objects);
    }
  }

  public DrawingDocument(UserSettings settings, EditorConfig policy) {
    this(
        new DocumentSnapshot(
            UUID.randomUUID().toString(),
            0,
            new CanvasSettings(
                new Bounds(0, 0, settings.canvas().width(), settings.canvas().height()),
                settings.canvas().background()),
            List.of(new Layer(UUID.randomUUID().toString(), "Layer 1", true, List.of()))),
        policy);
  }

  public DrawingDocument(DocumentSnapshot snapshot, EditorConfig policy) {
    this.policy = policy;
    id = snapshot.id();
    canvas = snapshot.canvas();
    revision = snapshot.revision();
    savedRevision = revision;
    revisionSequence = revision;
    validateBounds(canvas.bounds());
    Set<String> ids = objectIds, layerIds = new HashSet<>();
    for (Layer layer : snapshot.layers()) {
      if (!layerIds.add(layer.id())) throw new IllegalArgumentException("Duplicate layer ID");
      for (var object : layer.objects()) {
        validateObject(object);
        if (!ids.add(object.id())) throw new IllegalArgumentException("Duplicate object ID");
        objectCount++;
        pointCount += object.pointCount();
      }
      layers.add(new MutableLayer(layer));
    }
    checkLimits(objectCount, pointCount);
  }

  private void owner() {
    if (Thread.currentThread() != owner)
      throw new IllegalStateException("Document access requires owner thread");
  }

  public String id() {
    return id;
  }

  public long revision() {
    return revision;
  }

  public boolean dirty() {
    return revision != savedRevision;
  }

  public void markSaved(long revision) {
    owner();
    savedRevision = revision;
  }

  public void markRecovered() {
    owner();
    savedRevision = -1;
  }

  public void restoreRevision(long value) {
    owner();
    revision = value;
  }

  public void setLayers(List<Layer> values) {
    owner();
    DrawingDocument validated =
        new DrawingDocument(new DocumentSnapshot(id, revision, canvas, values), policy);
    layers.clear();
    layers.addAll(validated.layers);
    objectIds.clear();
    objectIds.addAll(validated.objectIds);
    objectCount = validated.objectCount;
    pointCount = validated.pointCount;
    revision = ++revisionSequence;
  }

  public CanvasSettings canvas() {
    return canvas;
  }

  public List<Layer> layers() {
    owner();
    return layers.stream().map(MutableLayer::snapshot).toList();
  }

  public List<DrawingObject> objects(int layer) {
    owner();
    return Collections.unmodifiableList(layers.get(layer).objects);
  }

  public int layerCount() {
    return layers.size();
  }

  public boolean layerVisible(int layer) {
    return layers.get(layer).visible;
  }

  public String layerName(int layer) {
    return layers.get(layer).name;
  }

  public DocumentSnapshot snapshot() {
    owner();
    return new DocumentSnapshot(id, revision, canvas, layers());
  }

  public void replace(
      int layer, int index, DrawingObject before, DrawingObject after, Bounds nextBounds) {
    owner();
    if (before == null && after == null)
      throw new IllegalArgumentException("An edit needs an object");
    if (revisionSequence == Long.MAX_VALUE)
      throw new IllegalStateException("Document revision limit reached");
    var list = layers.get(layer).objects;
    if (before != null && (index >= list.size() || list.get(index) != before))
      throw new IllegalStateException("Stale edit");
    if (before == null && (index < 0 || index > list.size()))
      throw new IllegalArgumentException("Invalid insertion");
    if (after != null) validateObject(after);
    if (after != null
        && (before == null || !after.id().equals(before.id()))
        && objectIds.contains(after.id()))
      throw new IllegalArgumentException("Duplicate object ID");
    validateBounds(nextBounds);
    CanvasSettings nextCanvas = new CanvasSettings(nextBounds, canvas.background());
    int nextObjects = objectCount + (before == null ? 1 : 0) - (after == null ? 1 : 0);
    int nextPoints =
        pointCount
            - (before == null ? 0 : before.pointCount())
            + (after == null ? 0 : after.pointCount());
    checkLimits(nextObjects, nextPoints);
    if (before == null) list.add(index, after);
    else if (after == null) list.remove(index);
    else list.set(index, after);
    if (before != null) objectIds.remove(before.id());
    if (after != null) objectIds.add(after.id());
    objectCount = nextObjects;
    pointCount = nextPoints;
    canvas = nextCanvas;
    revision = ++revisionSequence;
  }

  public Bounds expandedFor(DrawingObject object, UserSettings.Canvas preferences) {
    Bounds old = canvas.bounds(), b = object.bounds();
    if (!preferences.autoExpand()) {
      if (b.minX() < old.minX()
          || b.minY() < old.minY()
          || b.maxX() > old.maxX()
          || b.maxY() > old.maxY())
        throw new IllegalArgumentException(
            "Drawing exceeds the canvas; enable automatic expansion");
      return old;
    }
    double step = preferences.expansionStep(), margin = preferences.expansionMargin();
    double minX = Math.min(old.minX(), Math.floor((b.minX() - margin) / step) * step);
    double minY = Math.min(old.minY(), Math.floor((b.minY() - margin) / step) * step);
    double maxX = Math.max(old.maxX(), Math.ceil((b.maxX() + margin) / step) * step);
    double maxY = Math.max(old.maxY(), Math.ceil((b.maxY() + margin) / step) * step);
    Bounds expanded = new Bounds(minX, minY, maxX, maxY);
    validateBounds(expanded);
    return expanded;
  }

  public Optional<Bounds> contentBounds() {
    owner();
    return layers.stream()
        .filter(l -> l.visible)
        .flatMap(l -> l.objects.stream())
        .map(DrawingObject::bounds)
        .reduce(Bounds::union);
  }

  private void validateObject(DrawingObject object) {
    if (object.pointCount() > policy.maxStrokePoints())
      throw new IllegalArgumentException("Stroke exceeds point limit");
    validateBounds(object.bounds());
  }

  private void validateBounds(Bounds b) {
    double limit = policy.coordinateLimit();
    if (b.minX() < -limit || b.minY() < -limit || b.maxX() > limit || b.maxY() > limit)
      throw new IllegalArgumentException("Workspace coordinate limit reached");
  }

  private void checkLimits(int objects, int points) {
    if (objects > policy.maxObjects() || points > policy.maxPoints())
      throw new IllegalArgumentException("Document resource limit reached");
  }
}
