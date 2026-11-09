package com.paintstudio.app.config;

import java.util.List;
import com.paintstudio.ui.splash.SplashConfig;

public record AppConfig(String name, String version, String description,
        SplashConfig splash, double windowWidth, double windowHeight,
        String defaultTheme, String iconsDirectory, List<Integer> iconSizes) {
    public AppConfig {
        iconSizes = List.copyOf(iconSizes);
    }
}
