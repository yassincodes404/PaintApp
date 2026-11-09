package com.paintstudio.app.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import com.paintstudio.common.logging.AppLogger;
import com.paintstudio.common.logging.LogCategory;
import java.util.Map;

import com.paintstudio.ui.splash.SplashConfig;
import javafx.util.Duration;

public final class ConfigLoader {
    private static final AppLogger LOGGER = AppLogger.getInstance();
    private final Properties properties;
    private final Map<String, String> origins = new java.util.LinkedHashMap<>();

    private ConfigLoader(Properties properties) {
        this.properties = properties;
    }

    private static class Defaults {
        private static final AppConfig CONFIG = readResource();
    }

    public static AppConfig load() {
        return Defaults.CONFIG;
    }

    private static AppConfig readResource() {
        Properties properties = new Properties();
        try (var stream = ConfigLoader.class.getResourceAsStream("/config/app.properties")) {
            if (stream == null) {
                LOGGER.warn(LogCategory.CONFIGURATION, ConfigLoader.class, "configuration.warning", Map.of("message", "Missing app.properties; using built-in defaults"));
            } else {
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                LOGGER.info(LogCategory.CONFIGURATION, ConfigLoader.class, "configuration.loaded",
                        Map.of("resource", "/config/app.properties"));
            }
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn(LogCategory.CONFIGURATION, ConfigLoader.class, "configuration.warning", Map.of("message", "Cannot read app.properties; using built-in defaults: " + exception.getMessage()));
            properties.clear();
        }
        return load(properties);
    }

    public static AppConfig load(Properties properties) {
        return new ConfigLoader(properties).parse();
    }

    private AppConfig parse() {
        String version = text("app.version", "development");
        if (version.contains("${")) {
            invalid("app.version");
            version = "development";
        }
        String theme = text("theme.default", "dark");
        if (!List.of("dark", "light").contains(theme)) {
            invalid("theme.default");
            theme = "dark";
        }
        AppConfig result = new AppConfig(text("app.name", "Paint Studio"), version,
                text("app.description", "JavaFX Drawing Editor"),
                new SplashConfig(bool("splash.enabled", true),
                        path("splash.animation", "/icons/app-512.png"),
                        Duration.millis(number("splash.duration.ms", 2000, true)),
                        number("splash.width", 480, false), number("splash.height", 420, false),
                        bool("splash.show-name", true), bool("splash.show-version", true)),
                number("window.width", 1200, false), number("window.height", 800, false),
                theme, path("icons.directory", "/icons"), sizes());
        var logging = LOGGER.configuration();
        if (logging != null && logging.flag("startup.configuration")) {
            origins.forEach((key, origin) -> LOGGER.info(LogCategory.CONFIGURATION, ConfigLoader.class,
                    "application.configuration.origin", Map.of("key", key, "origin", origin)));
        }
        return result;
    }

    private String text(String key, String fallback) {
        String value = properties.getProperty(key, "").trim();
        origins.put(key, value.isEmpty() ? "built-in" : "properties");
        return value.isEmpty() ? fallback : value;
    }

    private String path(String key, String fallback) {
        String value = text(key, fallback);
        if (!value.startsWith("/") || value.contains("..") || value.contains("\\")) {
            invalid(key);
            return fallback;
        }
        return value;
    }

    private boolean bool(String key, boolean fallback) {
        String value = text(key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            invalid(key);
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    private double number(String key, double fallback, boolean allowZero) {
        try {
            double value = Double.parseDouble(text(key, Double.toString(fallback)));
            if (!Double.isFinite(value) || value < 0 || (!allowZero && value == 0)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            invalid(key);
            return fallback;
        }
    }

    private List<Integer> sizes() {
        try {
            List<Integer> sizes = Arrays.stream(text("icons.sizes", "16,32,48,256,512").split(",", -1))
                    .map(String::trim).map(Integer::parseInt).distinct().toList();
            if (sizes.stream().anyMatch(size -> size <= 0)) {
                throw new NumberFormatException();
            }
            return sizes;
        } catch (NumberFormatException exception) {
            invalid("icons.sizes");
            return List.of(16, 32, 48, 256, 512);
        }
    }

    private void invalid(String key) {
        origins.put(key, "built-in fallback");
        LOGGER.warn(LogCategory.CONFIGURATION, ConfigLoader.class, "configuration.warning", Map.of("message", "Invalid configuration for " + key + "; using default"));
    }
}
