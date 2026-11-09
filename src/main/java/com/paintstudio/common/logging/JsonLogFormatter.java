package com.paintstudio.common.logging;

import java.util.*;

/** Small JSON encoder for the deliberately limited log value types. */
public final class JsonLogFormatter {
    private JsonLogFormatter() {}

    public static String format(LogEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", event.timestamp().toString());
        fields.put("sequence", event.sequence());
        fields.put("sessionId", event.sessionId());
        fields.put("level", event.level().name());
        fields.put("category", event.category().name().toLowerCase(Locale.ROOT));
        fields.put("source", event.source());
        fields.put("event", event.event());
        fields.put("thread", event.thread());
        fields.put("context", event.context());
        return encode(fields);
    }

    public static Map<String, Object> snapshot(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        int count = 0;
        int remaining = 24000;
        for (var entry : source.entrySet()) {
            if (count++ == 64 || remaining <= 0) {
                copy.put("_truncated", true);
                break;
            }
            String key = text(entry.getKey());
            if (key.length() > 128) key = key.substring(0, 128) + " [truncated]";
            Object item = value(entry.getValue());
            if (item instanceof String text && text.length() > remaining) {
                item = text.substring(0, remaining) + " [truncated]";
            }
            remaining -= key.length() + String.valueOf(item).length();
            copy.put(key, item);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object value(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) return value;
        if (value instanceof Double number && Double.isFinite(number)) return number;
        if (value instanceof Float number && Float.isFinite(number)) return number;
        return text(String.valueOf(value));
    }

    public static String text(String text) {
        if (text == null) return "null";
        return text.length() <= 8192 ? text : text.substring(0, 8192) + " [truncated]";
    }

    private static String encode(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringJoiner result = new StringJoiner(",", "{", "}");
            map.forEach((key, item) -> result.add(quote(key.toString()) + ":" + encode(item)));
            return result.toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 32 || Character.isSurrogate(character)) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
