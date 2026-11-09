package com.paintstudio.ui.component;

import java.util.List;
import javafx.css.*;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/** Theme-aware line icons drawn on a shared 24-unit grid. */
public final class WorkspaceIcon extends StackPane {
  public enum Kind {
    PALETTE(
        "M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1-4 1.5 1.5 0 0 1 1-3h2a4 4 0 0 0 4-4c0-4-4-7-9-7Z M7"
            + " 11h.1 M10 7h.1 M15 7h.1 M17 10h.1"),
    NEW("M6 3h8l4 4v14H6Z M14 3v5h4 M9 14h6 M12 11v6"),
    OPEN("M3 8V6h6l2 2h9v3 M3 10h19l-3 10H3Z"),
    SAVE("M4 4h13l3 3v13H4Z M8 4v5h8V4 M8 20v-7h8v7"),
    SAVE_AS("M15 4H4v16h8 M8 4v5h6 M14 17l5-5 3 3-5 5-4 1Z"),
    EXPORT("M12 3H5v18h14v-7 M13 4h7v7 M20 4l-9 9"),
    UNDO("M9 5 4 10l5 5 M4 10h10a5 5 0 0 1 5 5v3"),
    REDO("m15 5 5 5-5 5 M20 10H10a5 5 0 0 0-5 5v3"),
    SETTINGS("M4 6h7 M15 6h5 M4 12h3 M11 12h9 M4 18h11 M19 18h1 M11 4v4 M7 10v4 M15 16v4"),
    BRUSH("m12 13 7-9 2 2-9 8 M12 13c-3-2-6 0-6 3 0 2-1 3-3 4 5 1 10-1 9-7Z"),
    RECTANGLE("M4 6h16v12H4Z"),
    ELLIPSE("M4 12a8 6 0 1 0 16 0 8 6 0 1 0-16 0Z"),
    SELECT("M4 9V4h5 M15 4h5v5 M20 15v5h-5 M9 20H4v-5"),
    ERASER(
        "m4 13 8-9a2 2 0 0 1 3 0l5 5a2 2 0 0 1 0 3l-7 8H9l-5-4a2 2 0 0 1 0-3Z M8 9l9 8 M13 20h8"),
    PAN(
        "M8 13V6a1.5 1.5 0 0 1 3 0v5-7a1.5 1.5 0 0 1 3 0v7-5a1.5 1.5 0 0 1 3 0v6-3a1.5 1.5 0 0 1 3"
            + " 0v5a7 7 0 0 1-7 7h-1c-3 0-4-2-6-4l-2-3a1.5 1.5 0 0 1 2-2l2 2"),
    RECOLOR("M12 3c2 3 5 6 5 9a5 5 0 0 1-10 0c0-3 3-6 5-9Z M3 20h18"),
    HOME("M3 11l9-7 9 7 M5 10v10h5v-5h4v5h5V10"),
    EXPAND("M14 4h6v6 M20 4l-6 6 M10 20H4v-6 M4 20l6-6"),
    COLLAPSE("m20 4-6 6 M14 4v6h6 M4 20l6-6 M4 14h6v6"),
    ZOOM_IN("M15 15l5 5 M9.5 4a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z M7 9.5h5 M9.5 7v5"),
    ZOOM_OUT("M15 15l5 5 M9.5 4a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z M7 9.5h5"),
    CENTER("M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4 M8 12h8 M12 8v8"),
    CLOSE("m6 6 12 12 M18 6 6 18");

    private final String path;

    Kind(String path) {
      this.path = path;
    }
  }

  private static final StyleablePropertyFactory<WorkspaceIcon> STYLES =
      new StyleablePropertyFactory<>(StackPane.getClassCssMetaData());
  private static final CssMetaData<WorkspaceIcon, Color> INK =
      STYLES.createColorCssMetaData("-studio-icon-color", i -> i.ink, Color.WHITE);
  private final StyleableObjectProperty<Color> ink =
      new SimpleStyleableObjectProperty<>(INK, this, "ink", Color.WHITE);

  public WorkspaceIcon(Kind kind, int size) {
    setMinSize(size, size);
    setPrefSize(size, size);
    setMaxSize(size, size);
    setMouseTransparent(true);
    getStyleClass().add("workspace-icon");
    SVGPath glyph = new SVGPath();
    glyph.setContent(kind.path);
    glyph.setFill(null);
    glyph.strokeProperty().bind(ink);
    glyph.setStrokeWidth(1.65);
    glyph.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
    glyph.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
    double scale = size / 24.0;
    glyph.setScaleX(scale);
    glyph.setScaleY(scale);
    getChildren().add(glyph);
  }

  @Override
  public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
    return STYLES.getCssMetaData();
  }

  public static <T extends ButtonBase> T decorate(T button, Kind icon, String label, int size) {
    button.setText("");
    button.setGraphic(new WorkspaceIcon(icon, size));
    button.setAccessibleText(label);
    button.setTooltip(new Tooltip(label));
    if (!button.getStyleClass().contains("icon-button")) button.getStyleClass().add("icon-button");
    return button;
  }
}
