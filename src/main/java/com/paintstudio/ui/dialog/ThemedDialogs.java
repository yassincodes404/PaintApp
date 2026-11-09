package com.paintstudio.ui.dialog;

import com.paintstudio.app.ThemeManager;
import com.paintstudio.ui.component.WorkspaceIcon;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Shared CSS title strip for every application-owned dialog. */
public final class ThemedDialogs {
  private ThemedDialogs() {}

  public static void install(Dialog<?> dialog, Window owner) {
    if (owner != null) dialog.initOwner(owner);
    dialog.initStyle(StageStyle.UNDECORATED);
    DialogPane pane = dialog.getDialogPane();
    if (dialog instanceof Alert) {
      pane.getStyleClass().removeAll("confirmation", "information", "warning", "error");
      pane.setGraphic(null);
    }
    pane.getStyleClass().add("themed-dialog");
    Label title = new Label();
    title.textProperty().bind(dialog.titleProperty());
    title.getStyleClass().add("dialog-caption");
    HBox.setHgrow(title, Priority.ALWAYS);
    title.setMaxWidth(Double.MAX_VALUE);
    Button close =
        WorkspaceIcon.decorate(new Button(), WorkspaceIcon.Kind.CLOSE, "Close dialog", 16);
    close.getStyleClass().add("dialog-close");
    close.setOnAction(e -> dialog.close());
    Label subtitle = new Label(dialog.getHeaderText());
    subtitle.setWrapText(true);
    subtitle.getStyleClass().add("dialog-heading");
    subtitle.visibleProperty().bind(subtitle.textProperty().isNotEmpty());
    subtitle.managedProperty().bind(subtitle.visibleProperty());
    HBox bar = new HBox(12, title, close);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("dialog-titlebar");
    pane.setHeader(new VBox(bar, subtitle));
    double[] offset = new double[2];
    title.setOnMousePressed(
        e -> {
          Window window = pane.getScene().getWindow();
          offset[0] = e.getScreenX() - window.getX();
          offset[1] = e.getScreenY() - window.getY();
        });
    title.setOnMouseDragged(
        e -> {
          Window window = pane.getScene().getWindow();
          window.setX(e.getScreenX() - offset[0]);
          window.setY(e.getScreenY() - offset[1]);
        });
    ThemeManager.apply(pane);
  }
}
