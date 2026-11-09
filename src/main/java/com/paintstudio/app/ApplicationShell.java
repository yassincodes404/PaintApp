package com.paintstudio.app;

import java.util.List;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Themed window chrome. All interaction runs on the JavaFX thread. */
public final class ApplicationShell extends BorderPane {
    private final SimpleBooleanProperty chromeHidden = new SimpleBooleanProperty();

    public void bindChromeHidden(ObservableBooleanValue hidden) {
        chromeHidden.bind(hidden);
    }
    private double offsetX, offsetY, startX, startY, startWidth, startHeight;
    private boolean left, right, top, bottom, resizing;
    private double restoredWidth = 1200;

    public ApplicationShell(Stage stage, Node editor) {
        this(stage, editor, AppIconManager.load(com.paintstudio.app.config.ConfigLoader.load()));
    }

    public ApplicationShell(Stage stage, Node editor, List<Image> icons) {
        getStyleClass().add("application-shell");
        HBox header = new HBox();
        header.getStyleClass().add("window-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox identity = new HBox(10);
        identity.getStyleClass().add("window-identity");
        identity.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(identity, Priority.ALWAYS);
        if (!icons.isEmpty()) {
            Image headerIcon = icons.stream()
                    .min(java.util.Comparator.comparingDouble(image -> Math.abs(image.getWidth() - 32)))
                    .orElseThrow();
            ImageView icon = new ImageView(headerIcon);
            icon.setFitWidth(23);
            icon.setFitHeight(23);
            identity.getChildren().add(icon);
        }
        Label title = new Label();
        title.textProperty().bind(stage.titleProperty());
        title.getStyleClass().add("window-title");
        identity.getChildren().add(title);
        Button minimize = control("\u2014", "Minimize", () -> stage.setIconified(true));
        Button maximize = control("\u25a1", "Maximize", () -> toggleMaximize(stage));
        stage.maximizedProperty().addListener((observable, old, maximized) -> {
            maximize.setText(maximized ? "\u2750" : "\u25a1");
            String action = maximized ? "Restore" : "Maximize";
            maximize.setAccessibleText(action);
            maximize.setTooltip(new Tooltip(action));
        });
        Button close = control("\u00d7", "Close", () ->
                stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        close.getStyleClass().add("window-close");
        header.getChildren().addAll(identity, minimize, maximize, close);
        identity.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            offsetX = event.getScreenX() - stage.getX();
            offsetY = event.getScreenY() - stage.getY();
        });
        identity.setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) return;
            if (stage.isMaximized()) {
                double fraction = Math.max(0, Math.min(1, offsetX / stage.getWidth()));
                stage.setMaximized(false);
                offsetX = restoredWidth * fraction;
                offsetY = Math.min(offsetY, 24);
            }
            stage.setX(event.getScreenX() - offsetX);
            stage.setY(event.getScreenY() - offsetY);
        });
        identity.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) toggleMaximize(stage);
        });
        header.visibleProperty().bind(stage.fullScreenProperty().not().and(chromeHidden.not()));
        header.managedProperty().bind(header.visibleProperty());
        setTop(header);
        setCenter(editor);
        installResize(stage);
    }

    private void toggleMaximize(Stage stage) {
        if (!stage.isMaximized()) restoredWidth = stage.getWidth();
        stage.setMaximized(!stage.isMaximized());
    }

    private static Button control(String symbol, String action, Runnable handler) {
        Button button = new Button(symbol);
        button.getStyleClass().add("window-control");
        button.setAccessibleText(action);
        button.setTooltip(new Tooltip(action));
        button.setOnAction(event -> handler.run());
        return button;
    }

    private void edges(MouseEvent event, Stage stage) {
        boolean enabled = stage.isResizable() && !stage.isMaximized() && !stage.isFullScreen();
        left = enabled && event.getSceneX() < 6;
        right = enabled && event.getSceneX() >= stage.getScene().getWidth() - 6;
        top = enabled && event.getSceneY() < 6;
        bottom = enabled && event.getSceneY() >= stage.getScene().getHeight() - 6;
    }

    private void installResize(Stage stage) {
        addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            edges(event, stage);
            Cursor cursor = Cursor.DEFAULT;
            if (left || right) cursor = Cursor.H_RESIZE;
            if (top || bottom) cursor = Cursor.V_RESIZE;
            if ((left && top) || (right && bottom)) cursor = Cursor.NW_RESIZE;
            if ((right && top) || (left && bottom)) cursor = Cursor.NE_RESIZE;
            setCursor(cursor);
        });
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!event.isPrimaryButtonDown()) return;
            edges(event, stage);
            resizing = left || right || top || bottom;
            if (!resizing) return;
            offsetX = event.getScreenX(); offsetY = event.getScreenY();
            startX = stage.getX(); startY = stage.getY();
            startWidth = stage.getWidth(); startHeight = stage.getHeight();
            event.consume();
        });
        addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!resizing) return;
            double dx = event.getScreenX() - offsetX, dy = event.getScreenY() - offsetY;
            if (left || right) {
                double width = Math.max(stage.getMinWidth(), Math.min(stage.getMaxWidth(),
                        startWidth + (left ? -dx : dx)));
                stage.setWidth(width);
                if (left) stage.setX(startX + startWidth - width);
            }
            if (top || bottom) {
                double height = Math.max(stage.getMinHeight(), Math.min(stage.getMaxHeight(),
                        startHeight + (top ? -dy : dy)));
                stage.setHeight(height);
                if (top) stage.setY(startY + startHeight - height);
            }
            event.consume();
        });
        addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizing) { resizing = false; setCursor(Cursor.DEFAULT); event.consume(); }
        });
    }
}
