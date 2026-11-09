package com.paintstudio.common.logging;

import java.time.Instant;
import java.util.Map;

public record LogEvent(Instant timestamp, long sequence, String sessionId, LogLevel level,
        LogCategory category, String source, String event, String thread, Map<String, Object> context) {
    public LogEvent { context = Map.copyOf(context); }
}
