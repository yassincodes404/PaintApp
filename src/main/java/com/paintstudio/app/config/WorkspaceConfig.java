package com.paintstudio.app.config;

import java.io.*;
import java.util.Properties;

public record WorkspaceConfig(int recentLimit, int iconSize, String defaultInk) {
  public static WorkspaceConfig load() {
    Properties p = new Properties();
    try (var stream = WorkspaceConfig.class.getResourceAsStream("/config/workspace.properties")) {
      if (stream != null) p.load(stream);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot load workspace configuration", error);
    }
    return new WorkspaceConfig(
        Integer.parseInt(p.getProperty("recent.limit", "12")),
        Integer.parseInt(p.getProperty("icons.size", "18")),
        p.getProperty("ink.default", "#F2F0ED"));
  }

  public WorkspaceConfig {
    if (recentLimit < 1
        || recentLimit > 30
        || iconSize < 16
        || iconSize > 32
        || defaultInk == null
        || !defaultInk.matches("#[0-9a-fA-F]{6}"))
      throw new IllegalArgumentException("Invalid workspace configuration");
  }
}
