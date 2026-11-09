package com.paintstudio.app;

import java.nio.file.Path;

public final class AppPaths {
    private AppPaths() {}

    public static Path root() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "PaintStudio");
        return Path.of(System.getProperty("user.home"), ".paintstudio");
    }

    public static Path config() { return root().resolve("config"); }
    public static Path logs() { return root().resolve("logs"); }
    public static Path instanceLock() { return state().resolve("application.lock"); }
    public static Path state() { return root().resolve("state"); }
    public static Path recovery() { return root().resolve("recovery"); }
}
