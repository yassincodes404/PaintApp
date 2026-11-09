package com.paintstudio.ui.splash;

import javafx.util.Duration;

public record SplashConfig(boolean enabled, String animation, Duration duration,
        double width, double height, boolean showName, boolean showVersion) {
    public SplashConfig {
        if (animation == null || !animation.startsWith("/")) {
            throw new IllegalArgumentException("Animation must be an absolute classpath resource");
        }
        if (duration == null || !Double.isFinite(duration.toMillis()) || duration.toMillis() < 0) {
            throw new IllegalArgumentException("Duration must be finite and nonnegative");
        }
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Splash dimensions must be positive and finite");
        }
    }

    public SplashConfig withDuration(Duration duration) {
        return new SplashConfig(enabled, animation, duration, width, height, showName, showVersion);
    }
}
