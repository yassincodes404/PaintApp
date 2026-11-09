package com.paintstudio.app;

import com.paintstudio.app.config.ConfigLoader;

public final class AppInfo {
    private AppInfo() {}

    public static String getName() { return ConfigLoader.load().name(); }
    public static String getVersion() { return ConfigLoader.load().version(); }
    public static String getDescription() { return ConfigLoader.load().description(); }
    public static String getDisplayName() { return getDisplayName(false); }
    public static String getDisplayName(boolean includeVersion) {
        return includeVersion ? getName() + " " + getVersion() : getName();
    }
}
