package com.paintstudio.app;

import java.util.ArrayList;
import java.util.List;
import com.paintstudio.common.logging.AppLogger;
import com.paintstudio.common.logging.LogCategory;
import java.util.Map;
import com.paintstudio.app.config.AppConfig;
import com.paintstudio.app.config.ConfigLoader;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public final class AppIconManager {
    private static final AppLogger LOGGER = AppLogger.getInstance();

    private AppIconManager() {}

    public static void applyTo(Stage stage) {
        applyTo(stage, ConfigLoader.load());
    }

    public static void applyTo(Stage stage, AppConfig config) {
        List<Image> icons = load(config);
        if (!icons.isEmpty()) stage.getIcons().setAll(icons);
    }

    public static List<Image> load(AppConfig config) {
        var icons = new ArrayList<Image>();
        for (int size : config.iconSizes()) {
            String path = config.iconsDirectory().replaceAll("/+$", "") + "/app-" + size + ".png";
            try (var stream = AppIconManager.class.getResourceAsStream(path)) {
                if (stream == null) {
                    LOGGER.warn(LogCategory.RESOURCE, AppIconManager.class, "resource.warning", Map.of("message", "Missing application icon: " + path));
                    continue;
                }
                Image image = new Image(stream);
                if (image.isError()) {
                    LOGGER.warn(LogCategory.RESOURCE, AppIconManager.class, "resource.warning", Map.of("message", "Failed to decode application icon: " + path));
                } else {
                    icons.add(image);
                }
            } catch (Exception exception) {
                LOGGER.warn(LogCategory.RESOURCE, AppIconManager.class, "resource.warning", Map.of("message", "Unable to load icon " + path + ": " + exception.getMessage()));
            }
        }
        if (!icons.isEmpty()) {
            LOGGER.info(LogCategory.RESOURCE, AppIconManager.class, "resources.icons.loaded", Map.of("count", icons.size()));
        }
        return List.copyOf(icons);
    }
}
