package com.paintstudio.ui.splash;

import java.util.Objects;
import com.paintstudio.common.logging.AppLogger;
import com.paintstudio.common.logging.LogCategory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javafx.application.Platform;
import com.paintstudio.app.AppInfo;
import com.paintstudio.app.ThemeManager;
import com.paintstudio.app.config.ConfigLoader;
import javafx.animation.PauseTransition;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.Interpolator;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public final class SplashScreen {
    private static final AppLogger LOGGER = AppLogger.getInstance();

    private SplashScreen() {}

    public static void show() { show(() -> {}); }
    public static void show(Runnable onFinished) { show(ConfigLoader.load().splash(), onFinished); }
    public static void show(Duration duration) { show(duration, () -> {}); }
    public static void show(Duration duration, Runnable onFinished) {
        show(ConfigLoader.load().splash().withDuration(duration), onFinished);
    }

    // Call on the JavaFX application thread, like other Stage operations.
    public static void show(SplashConfig config, Runnable onFinished) {
        show(config, CompletableFuture.completedFuture(null), onFinished);
    }

    public static void show(SplashConfig config, CompletionStage<?> preparation, Runnable onFinished) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(preparation);
        Objects.requireNonNull(onFinished);
        if (!config.enabled()) {
            preparation.whenComplete((value, error) -> runOnFxThread(onFinished));
            return;
        }
        VBox content = new VBox(8);
        content.setAlignment(Pos.CENTER);
        content.setStyle("-fx-background-color: transparent;");
        var resource = SplashScreen.class.getResource(config.animation());
        if (resource != null) {
            Image image = new Image(resource.toExternalForm());
            if (!image.isError()) {
                ImageView view = new ImageView(image);
                view.setPreserveRatio(true);
                view.setFitWidth(Math.min(260, config.width() - 32));
                view.setSmooth(true);
                view.setFitHeight(Math.max(1, config.height() - 140));
                content.getChildren().add(view);
            } else {
                LOGGER.warn(LogCategory.RESOURCE, SplashScreen.class, "resource.warning", Map.of("message", "Failed to decode splash animation: " + config.animation()));
            }
        } else {
            LOGGER.warn(LogCategory.RESOURCE, SplashScreen.class, "resource.warning", Map.of("message", "Missing splash animation: " + config.animation()));
        }
        if (config.showName()) content.getChildren().add(createLabel(AppInfo.getName(), true));
        if (config.showVersion()) content.getChildren().add(createLabel("v" + AppInfo.getVersion(), false));
        Stage splash = new Stage(StageStyle.TRANSPARENT);
        Scene scene = new Scene(content, config.width(), config.height(), Color.TRANSPARENT);
        ThemeManager.apply(scene);
        splash.setScene(scene);
        // Keep the configured total display time, including the entrance animation.
        Duration entranceDuration = Duration.millis(Math.min(450, config.duration().toMillis()));
        FadeTransition fade = new FadeTransition(entranceDuration, content);
        fade.setFromValue(0);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(entranceDuration, content);
        scale.setFromX(0.94);
        scale.setFromY(0.94);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition entrance = new ParallelTransition(fade, scale);
        PauseTransition pause = new PauseTransition(config.duration());
        splash.setOnHidden(event -> {
            pause.stop();
            entrance.stop();
            LOGGER.info(LogCategory.STARTUP, SplashScreen.class, "splash.hidden", Map.of());
            preparation.whenComplete((value, error) -> runOnFxThread(onFinished));
        });
        pause.setOnFinished(event -> preparation.whenComplete((value, error) -> runOnFxThread(splash::hide)));
        content.setOpacity(0);
        splash.show();
        LOGGER.info(LogCategory.STARTUP, SplashScreen.class, "splash.shown", Map.of("durationMs", config.duration().toMillis()));
        entrance.play();
        pause.play();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private static Label createLabel(String text, boolean title) {
        Label label = new Label(text);
        label.getStyleClass().add(title ? "splash-title" : "splash-version");
        return label;
    }
}
