package com.paintstudio.ui.dialog;

import com.paintstudio.app.AppInfo;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.geometry.Pos;
import javafx.stage.Stage;

/** A startup-safe message, including when the logger has never started. */
public final class StartupMessage {
    private StartupMessage() {}

    public static void show(boolean duplicate, String failureMessage) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(AppInfo.getName());
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().add(ButtonType.OK);
        pane.getStyleClass().add("startup-dialog");
        pane.setPrefWidth(480);

        Label brand = new Label(AppInfo.getName());
        brand.getStyleClass().add("dialog-brand");
        HBox identity = new HBox(12);
        identity.setAlignment(Pos.CENTER_LEFT);
        var resource = StartupMessage.class.getResource("/icons/app-48.png");
        if (resource != null) {
            Image image = new Image(resource.toExternalForm());
            if (!image.isError()) {
                ImageView icon = new ImageView(image);
                icon.setFitWidth(40);
                icon.setFitHeight(40);
                identity.getChildren().add(icon);
                ((Stage) pane.getScene().getWindow()).getIcons().add(image);
            }
        }
        identity.getChildren().add(brand);
        Label title = new Label(duplicate ? "Your studio is already open" : "Unable to open your studio");
        title.getStyleClass().add("dialog-title");
        title.setWrapText(true);
        Label message = new Label(duplicate
                ? "Return to the existing Paint Studio window to continue working."
                : failureMessage);
        message.getStyleClass().add("dialog-message");
        message.setWrapText(true);
        Label note = new Label(duplicate
                ? "To restart, close that window first, then open Paint Studio again."
                : "Check the application folder permissions, then try again.");
        note.getStyleClass().add("dialog-note");
        note.setWrapText(true);
        VBox content = new VBox(14, identity, title, message, note);
        content.getStyleClass().add("dialog-content");
        pane.setContent(content);
        ThemedDialogs.install(dialog, null);
        dialog.showAndWait();
    }
}
