package com.paintstudio.ui.component;

import com.paintstudio.drawing.tool.InkPreset;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/** Compact colored nibs inspired by physical drawing tools. */
public final class PenNib extends Group {
  public PenNib(InkPreset preset) {
    setMouseTransparent(true);
    String body =
        switch (preset) {
          case HIGHLIGHTER -> "M5 2h14v11l-3 3v4l-8 2v-6l-3-3Z";
          case CALLIGRAPHY -> "M6 2h12v13l-2 5H8l-2-5Z";
          case FOUNTAIN -> "M6 2h12v10l-6 10-6-10Z M12 15v7";
          case PENCIL -> "M7 2h10v11l-5 9-5-9Z M7 13h10";
          default -> "M6 2h12v10l-6 10-6-10Z";
        };
    String tip =
        switch (preset) {
          case HIGHLIGHTER -> "M5 2h14v5H5Z M8 16l8-2v6l-8 2Z";
          case CALLIGRAPHY -> "M6 2h12v5H6Z M7 15h10l-2 5H9Z";
          case FOUNTAIN -> "M6 2h12v5H6Z M9 15h6l-3 7Z";
          default -> "M6 2h12v5H6Z M9.5 17h5L12 22Z";
        };
    SVGPath ink = new SVGPath();
    ink.setContent(tip);
    ink.setFill(Color.web(preset.color));
    SVGPath outline = new SVGPath();
    outline.setContent(body);
    outline.getStyleClass().add("pen-nib-outline");
    getChildren().addAll(ink, outline);
  }
}
