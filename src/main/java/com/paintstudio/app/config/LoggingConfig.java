package com.paintstudio.app.config;

import java.util.Map;
import com.paintstudio.common.logging.LogCategory;
import com.paintstudio.common.logging.LogLevel;

/** Validated, immutable effective settings. Construct through LoggingConfigLoader. */
public final class LoggingConfig {
    private final Map<String, String> values;

    LoggingConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public Map<String, String> values() { return values; }
    public boolean flag(String key) { return Boolean.parseBoolean(values.get("logging." + key)); }
    public int number(String key) { return Integer.parseInt(values.get("logging." + key)); }
    public boolean enabled() { return flag("enabled") && (flag("console.enabled") || flag("file.enabled")); }
    public LogLevel level() { return LogLevel.valueOf(values.get("logging.level")); }
    public boolean accepts(LogLevel level, LogCategory category) {
        return enabled() && level.ordinal() >= level().ordinal()
                && flag("category." + category.name().toLowerCase(java.util.Locale.ROOT));
    }
}
