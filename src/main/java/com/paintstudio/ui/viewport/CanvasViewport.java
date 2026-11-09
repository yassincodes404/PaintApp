package com.paintstudio.ui.viewport;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.document.model.DrawingDocument;
import com.paintstudio.drawing.geometry.Bounds;
import com.paintstudio.drawing.model.*;
import com.paintstudio.drawing.renderer.DrawingRenderer;
import com.paintstudio.drawing.tool.*;
import com.paintstudio.editor.history.*;
import com.paintstudio.editor.interaction.InputSample;
import com.paintstudio.editor.state.ToolType;
import com.paintstudio.settings.UserSettings;
import java.util.List;
import java.util.function.Consumer;
import javafx.css.*;
import javafx.scene.canvas.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

/** All interaction and attached Canvas changes stay on the FX thread. */
public final class CanvasViewport extends Pane implements AutoCloseable {
  private static final StyleablePropertyFactory<CanvasViewport> STYLES =
      new StyleablePropertyFactory<>(Pane.getClassCssMetaData());
  private static final CssMetaData<CanvasViewport, Color> SELECTION =
      STYLES.createColorCssMetaData(
          "-studio-selection-color", v -> v.selectionColor, Color.CORNFLOWERBLUE);
  private static final CssMetaData<CanvasViewport, Color> GRID =
      STYLES.createColorCssMetaData("-studio-grid-color", v -> v.gridColor, Color.gray(.5, .2));
  private final StyleableObjectProperty<Color> selectionColor =
      new SimpleStyleableObjectProperty<>(SELECTION, this, "selectionColor", Color.CORNFLOWERBLUE);
  private final StyleableObjectProperty<Color> gridColor =
      new SimpleStyleableObjectProperty<>(GRID, this, "gridColor", Color.gray(.5, .2));

  @Override
  public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
    return STYLES.getCssMetaData();
  }

  private final Canvas content = new Canvas(), preview = new Canvas(), overlay = new Canvas();
  private final ViewportTransform transform = new ViewportTransform();
  private final RenderScheduler scheduler = new RenderScheduler(this::render);
  private final EditorConfig policy;
  private DrawingDocument document;
  private CommandManager history;
  private UserSettings settings;
  private ToolType tool = ToolType.BRUSH;
  private DrawingTool gesture;
  private DrawingObject selected, moving;
  private int selectedLayer, selectedIndex;
  private int activeLayer;
  private double pressX, pressY, lastX, lastY;
  private boolean panning, resizing, centered, contentDirty = true, pointLimit;
  private Color ink = Color.web("#222222");
  private double thickness = 6;
  private long renderedFrames, totalRenderNanos;
  private Runnable changed = () -> {};
  private Consumer<String> message = ignored -> {};

  public CanvasViewport(EditorConfig policy, UserSettings settings) {
    this.policy = policy;
    this.settings = settings;
    getStyleClass().add("canvas-viewport");
    selectionColor.addListener((o, a, b) -> invalidate());
    gridColor.addListener((o, a, b) -> invalidate());
    getChildren().addAll(content, preview, overlay);
    setFocusTraversable(true);
    setMinSize(100, 100);
    widthProperty().addListener((o, a, b) -> resize());
    heightProperty().addListener((o, a, b) -> resize());
    setOnMousePressed(this::pressed);
    setOnMouseDragged(this::dragged);
    setOnMouseReleased(this::released);
    setOnScroll(
        e -> {
          e.consume();
          if (!e.isShortcutDown() || drawingGestureActive()) return;
          transform.zoomAt(
              Math.pow(1.0015, e.getDeltaY()),
              e.getX(),
              e.getY(),
              this.settings.canvas().minZoom(),
              this.settings.canvas().maxZoom());
          invalidate();
          changed.run();
        });
    setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ESCAPE) {
            cancel();
            e.consume();
          } else if (e.getCode() == KeyCode.DELETE) {
            deleteSelection();
            e.consume();
          }
        });
  }

  public void onChanged(Runnable changed) {
    this.changed = changed;
  }

  public void onMessage(Consumer<String> message) {
    this.message = message;
  }

  public void setDocument(DrawingDocument d, CommandManager h) {
    cancel();
    document = d;
    history = h;
    selected = null;
    centered = false;
    resize();
  }

  public void configure(UserSettings s) {
    settings = s;
    invalidate();
  }

  public double zoom() {
    return transform.zoom();
  }

  public long renderedFrames() {
    return renderedFrames;
  }

  public long totalRenderNanos() {
    return totalRenderNanos;
  }

  public void setTool(ToolType value) {
    cancel();
    tool = value;
    com.paintstudio.common.logging.AppLogger.getInstance()
        .info(
            com.paintstudio.common.logging.LogCategory.ACTION,
            CanvasViewport.class,
            "tool.selected",
            java.util.Map.of("tool", value.name()));
  }

  public void setInk(Color color) {
    ink = color;
  }

  public void setThickness(double value) {
    thickness = value;
  }

  public void setActiveLayer(int layer) {
    cancel();
    activeLayer = layer;
    selected = null;
    invalidate();
  }

  public void center() {
    if (document != null) {
      transform.center(document.canvas().bounds(), getWidth(), getHeight());
      centered = true;
      invalidate();
      changed.run();
    }
  }

  public void zoom(double factor) {
    if (drawingGestureActive()) return;
    transform.zoomAt(
        factor,
        getWidth() / 2,
        getHeight() / 2,
        settings.canvas().minZoom(),
        settings.canvas().maxZoom());
    invalidate();
    changed.run();
  }

  public void undo() {
    cancel();
    history.undo();
    selected = null;
    invalidate();
    changed.run();
  }

  public void redo() {
    cancel();
    history.redo();
    selected = null;
    invalidate();
    changed.run();
  }

  public void recolorSelection() {
    if (selected != null) commit("recolor", selected, selected.recolor(DrawingRenderer.argb(ink)));
  }

  public void deleteSelection() {
    if (selected != null) commit("erase", selected, null);
  }

  public void invalidate() {
    contentDirty = true;
    scheduler.request();
  }

  private void resize() {
    for (Canvas c : new Canvas[] {content, preview, overlay}) {
      c.setWidth(getWidth());
      c.setHeight(getHeight());
    }
    if (!centered && document != null && getWidth() > 0 && getHeight() > 0) center();
    invalidate();
  }

  private InkPreset inkPreset = InkPreset.PEN;

  public void setInkPreset(InkPreset preset) {
    inkPreset = preset;
  }

  private InputSample sample(double x, double y, double pressure) {
    return new InputSample(transform.documentX(x), transform.documentY(y), pressure);
  }

  public enum PenAction {
    INK,
    PAN,
    ERASE
  }

  private boolean nativeContact;
  private boolean spacePan;

  public void setSpacePan(boolean active) {
    spacePan = active;
  }

  public boolean drawingGestureActive() {
    return nativeContact || gesture != null || panning || moving != null;
  }

  public boolean beginPen(InputSample point, PenAction action) {
    double x = point.x(), y = point.y();
    if (document == null || isDisabled() || !contains(x, y)) return false;
    if (spacePan || tool == ToolType.PAN) action = PenAction.PAN;
    else if (tool == ToolType.ERASER) action = PenAction.ERASE;
    if (action == PenAction.INK && tool != ToolType.BRUSH) return false;
    requestFocus();
    cancel();
    nativeContact = true;
    if (action == PenAction.PAN) {
      panning = true;
      lastX = x;
      lastY = y;
    } else if (action == PenAction.ERASE) {
      var position = point.at(transform.documentX(x), transform.documentY(y));
      pick(position.x(), position.y());
      deleteSelection();
    } else if (document.layerVisible(activeLayer)) {
      startBrush(point.at(transform.documentX(x), transform.documentY(y)));
    } else {
      message.accept("Show the active layer before drawing on it.");
    }
    return true;
  }

  public void movePen(InputSample point) {
    if (!nativeContact) return;
    if (panning) panTo(point.x(), point.y());
    else if (gesture instanceof BrushTool)
      appendSample(point.at(transform.documentX(point.x()), transform.documentY(point.y())));
  }

  public void finishPen() {
    if (!nativeContact) return;
    nativeContact = false;
    if (panning || gesture instanceof BrushTool) finishGesture();
  }

  private void startBrush(InputSample point) {
    pointLimit = false;
    gesture =
        new BrushTool(
            point,
            DrawingRenderer.argb(ink),
            thickness,
            policy.maxStrokePoints(),
            policy.inputSpacing() / transform.zoom(),
            inkPreset);
    scheduler.request();
  }

  private void appendSample(InputSample point) {
    if (pointLimit) return;
    try {
      gesture.drag(point);
    } catch (IllegalArgumentException error) {
      pointLimit = true;
      message.accept(error.getMessage());
    }
    scheduler.request();
  }

  private void pressed(MouseEvent e) {
    if (nativeContact) return;
    requestFocus();
    if (document == null) return;
    lastX = e.getX();
    lastY = e.getY();
    panning =
        e.getButton() == MouseButton.MIDDLE
            || (e.getButton() == MouseButton.PRIMARY && (spacePan || tool == ToolType.PAN));
    if (panning) return;
    if (e.getButton() != MouseButton.PRIMARY) return;
    if ((tool == ToolType.BRUSH || tool == ToolType.RECTANGLE || tool == ToolType.ELLIPSE)
        && !document.layerVisible(activeLayer)) {
      message.accept("Show the active layer before drawing on it.");
      return;
    }
    var p = sample(e.getX(), e.getY(), 1);
    pressX = p.x();
    pressY = p.y();
    pointLimit = false;
    if (tool == ToolType.SELECT && selected != null) {
      Bounds b = selected.bounds();
      resizing = Math.hypot(p.x() - b.maxX(), p.y() - b.maxY()) < 12 / transform.zoom();
      if (resizing) {
        moving = selected;
        invalidate();
        return;
      }
    }
    switch (tool) {
      case BRUSH -> startBrush(p);
      case RECTANGLE, ELLIPSE ->
          gesture =
              new ShapeTool(
                  p,
                  tool == ToolType.RECTANGLE ? ShapeType.RECTANGLE : ShapeType.ELLIPSE,
                  DrawingRenderer.argb(ink),
                  thickness);
      case SELECT, ERASER -> {
        pick(p.x(), p.y());
        moving = tool == ToolType.SELECT ? selected : null;
        invalidate();
      }
      default -> {}
    }
    scheduler.request();
  }

  private void panTo(double x, double y) {
    transform.pan(x - lastX, y - lastY);
    lastX = x;
    lastY = y;
    invalidate();
    changed.run();
  }

  private void dragged(MouseEvent e) {
    if (nativeContact) return;
    if (panning) {
      panTo(e.getX(), e.getY());
      return;
    }
    var p = sample(e.getX(), e.getY(), 1);
    if (gesture != null) {
      appendSample(p);
    } else if (selected != null && moving != null) {
      var b = selected.bounds();
      double sx = resizing ? Math.max(.01, (p.x() - b.minX()) / Math.max(.01, b.width())) : 1;
      double sy = resizing ? Math.max(.01, (p.y() - b.minY()) / Math.max(.01, b.height())) : 1;
      moving =
          selected.transform(
              resizing ? 0 : p.x() - pressX,
              resizing ? 0 : p.y() - pressY,
              sx,
              sy,
              b.minX(),
              b.minY());
    }
    scheduler.request();
  }

  private void released(MouseEvent e) {
    if (nativeContact) return;
    finishGesture();
  }

  private void finishGesture() {
    if (panning) {
      panning = false;
      return;
    }
    if (gesture != null) {
      var object = gesture.preview();
      gesture = null;
      selectedLayer = activeLayer;
      selectedIndex = document.objects(activeLayer).size();
      commit("draw", null, object);
    } else if (tool == ToolType.ERASER && selected != null) deleteSelection();
    else if (moving != null && moving != selected)
      commit(resizing ? "resize" : "move", selected, moving);
    moving = null;
    resizing = false;
    invalidate();
  }

  private void pick(double x, double y) {
    selected = null;
    for (int l = document.layerCount() - 1; l >= 0; l--) {
      if (!document.layerVisible(l)) continue;
      var objects = document.objects(l);
      for (int i = objects.size() - 1; i >= 0; i--)
        if (objects.get(i).hit(x, y, 6 / transform.zoom())) {
          selected = objects.get(i);
          selectedLayer = l;
          selectedIndex = i;
          return;
        }
    }
  }

  private void commit(String action, DrawingObject before, DrawingObject after) {
    try {
      Bounds old = document.canvas().bounds(),
          next = after == null ? old : document.expandedFor(after, settings.canvas());
      history.execute(
          new ObjectEdit(action, selectedLayer, selectedIndex, before, after, old, next));
      if (!old.equals(next))
        com.paintstudio.common.logging.AppLogger.getInstance()
            .info(
                com.paintstudio.common.logging.LogCategory.DOCUMENT,
                CanvasViewport.class,
                "canvas.expanded",
                java.util.Map.of("documentId", document.id(), "bounds", next.toString()));
      selected = after;
      changed.run();
    } catch (IllegalArgumentException error) {
      message.accept(error.getMessage());
    }
    moving = null;
    invalidate();
  }

  public void cancel() {
    nativeContact = false;
    gesture = null;
    moving = null;
    panning = false;
    resizing = false;
    invalidate();
  }

  private void reset(Canvas canvas) {
    var g = canvas.getGraphicsContext2D();
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, getWidth(), getHeight());
    double z = transform.zoom();
    g.setTransform(z, 0, 0, z, -transform.x() * z, -transform.y() * z);
  }

  private void render() {
    if (document == null) return;
    long start = System.nanoTime();
    Bounds visible = transform.visible(getWidth(), getHeight());
    if (contentDirty) {
      contentDirty = false;
      reset(content);
      var g = content.getGraphicsContext2D();
      Bounds bounds = document.canvas().bounds();
      g.setFill(Color.web(document.canvas().background()));
      g.fillRect(bounds.minX(), bounds.minY(), bounds.width(), bounds.height());
      if (settings.appearance().showGrid()) {
        double step = 64;
        while (step * transform.zoom() < 16) step *= 2;
        g.setStroke(gridColor.get());
        g.setLineWidth(1 / transform.zoom());
        for (double x = Math.floor(visible.minX() / step) * step; x <= visible.maxX(); x += step)
          g.strokeLine(x, visible.minY(), x, visible.maxY());
        for (double y = Math.floor(visible.minY() / step) * step; y <= visible.maxY(); y += step)
          g.strokeLine(visible.minX(), y, visible.maxX(), y);
      }
      for (int l = 0; l < document.layerCount(); l++)
        if (document.layerVisible(l))
          for (var object : document.objects(l))
            if (visible.intersects(object.bounds()) && !(moving != null && object == selected))
              DrawingRenderer.draw(g, object);
    }
    reset(preview);
    if (gesture != null) DrawingRenderer.draw(preview.getGraphicsContext2D(), gesture.preview());
    if (moving != null) DrawingRenderer.draw(preview.getGraphicsContext2D(), moving);
    reset(overlay);
    if (selected != null && tool == ToolType.SELECT) {
      var b = (moving == null ? selected : moving).bounds();
      var g = overlay.getGraphicsContext2D();
      double z = transform.zoom();
      g.setStroke(selectionColor.get());
      g.setFill(selectionColor.get());
      g.setLineWidth(1 / z);
      g.strokeRect(b.minX(), b.minY(), b.width(), b.height());
      g.fillRect(b.maxX() - 4 / z, b.maxY() - 4 / z, 8 / z, 8 / z);
    }
    renderedFrames++;
    totalRenderNanos += System.nanoTime() - start;
  }

  @Override
  public void close() {
    scheduler.close();
    document = null;
    gesture = null;
    selected = null;
    moving = null;
  }
}
