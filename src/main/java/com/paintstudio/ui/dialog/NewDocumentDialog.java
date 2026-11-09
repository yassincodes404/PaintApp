package com.paintstudio.ui.dialog;

import com.paintstudio.settings.*;
import java.util.Optional;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** New-document choices belong to the document, not to saved user defaults. */
public final class NewDocumentDialog {
  private NewDocumentDialog() {}

  public static Optional<UserSettings.Canvas> show(Window owner, UserSettings settings) {
    Dialog<UserSettings.Canvas> dialog = new Dialog<>();
    dialog.setTitle("New document");
    dialog.setHeaderText("Set up your canvas");
    ThemedDialogs.install(dialog, owner);
    var defaults = settings.canvas();
    TextField width = new TextField("" + defaults.width()),
        height = new TextField("" + defaults.height());
    ColorPicker background = new ColorPicker(javafx.scene.paint.Color.web(defaults.background()));
    Label error = new Label();
    error.getStyleClass().add("settings-error");
    error.setWrapText(true);
    GridPane grid = new GridPane();
    grid.setHgap(20);
    grid.setVgap(16);
    grid.addRow(0, new Label("Width"), width);
    grid.addRow(1, new Label("Height"), height);
    grid.addRow(2, new Label("Background"), background);
    Label note = new Label("These choices apply to this document.");
    note.setWrapText(true);
    note.getStyleClass().add("muted");
    var pane = dialog.getDialogPane();
    pane.setPrefWidth(440);
    pane.setContent(new VBox(18, grid, note, error));
    ButtonType create = new ButtonType("Create document", ButtonBar.ButtonData.OK_DONE);
    pane.getButtonTypes().setAll(create, ButtonType.CANCEL);
    javafx.beans.property.ObjectProperty<UserSettings.Canvas> result =
        new javafx.beans.property.SimpleObjectProperty<>();
    pane.lookupButton(create).setId("create-document");
    pane.lookupButton(create)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              try {
                var c =
                    new UserSettings.Canvas(
                        Integer.parseInt(width.getText()),
                        Integer.parseInt(height.getText()),
                        background.getValue().toString().replace("0x", "#"),
                        defaults.autoExpand(),
                        defaults.expansionStep(),
                        defaults.expansionMargin(),
                        defaults.minZoom(),
                        defaults.maxZoom());
                SettingsValidator.validate(
                    new UserSettings(1, settings.appearance(), c, settings.performance()));
                result.set(c);
              } catch (IllegalArgumentException failure) {
                e.consume();
                error.setText(failure.getMessage());
              }
            });
    dialog.setResultConverter(button -> button == create ? result.get() : null);
    return dialog.showAndWait();
  }
}
