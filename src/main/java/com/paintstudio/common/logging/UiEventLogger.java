package com.paintstudio.common.logging;

import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import java.util.Map;

/** FX-thread-owned registration. Observes input without consuming events. */
public final class UiEventLogger implements AutoCloseable {
    private static final Object REGISTRATION_KEY = new Object();
    private final Scene scene;
    private final AppLogger logger = AppLogger.getInstance();
    private final EventHandler<KeyEvent> keys = this::key;
    private final EventHandler<MouseEvent> mouse = this::mouse;
    private long lastMovement;
    private boolean closed;

    private UiEventLogger(Scene scene) {
        this.scene = scene;
        scene.addEventFilter(KeyEvent.ANY, keys);
        scene.addEventFilter(MouseEvent.ANY, mouse);
    }

    public static UiEventLogger attach(Scene scene) {
        Object existing = scene.getProperties().get(REGISTRATION_KEY);
        if (existing instanceof UiEventLogger registration) return registration;
        UiEventLogger registration = new UiEventLogger(scene);
        scene.getProperties().put(REGISTRATION_KEY, registration);
        return registration;
    }

    private void key(KeyEvent event) {
        if (!logger.accepts(LogLevel.DEBUG, LogCategory.INPUT)) return;
        var config = logger.configuration();
        if (event.getTarget() instanceof PasswordField) return;
        if (event.getEventType() == KeyEvent.KEY_TYPED) {
            if (config.flag("input.typed-text")) {
                logger.debug(LogCategory.INPUT, UiEventLogger.class, "input.text",
                        () -> Map.of("text", event.getCharacter()));
            }
            return;
        }
        boolean shortcut = event.isShortcutDown() || event.isAltDown();
        if (!config.flag("input.key-events") && !(shortcut && config.flag("input.shortcuts"))) return;
        logger.debug(LogCategory.INPUT, UiEventLogger.class, "input.key",
                () -> Map.of("type", event.getEventType().getName(), "key", event.getCode().name(),
                        "shift", event.isShiftDown(), "control", event.isControlDown(),
                        "alt", event.isAltDown(), "meta", event.isMetaDown()));
    }

    private void mouse(MouseEvent event) {
        if (!logger.accepts(LogLevel.TRACE, LogCategory.INPUT)) return;
        boolean movement = event.getEventType() == MouseEvent.MOUSE_MOVED
                || event.getEventType() == MouseEvent.MOUSE_DRAGGED;
        if (movement) {
            if (!logger.configuration().flag("input.mouse-movement")) return;
            long now = System.nanoTime();
            if (now - lastMovement < 100_000_000L) return;
            lastMovement = now;
        } else if (event.getEventType() != MouseEvent.MOUSE_PRESSED
                && event.getEventType() != MouseEvent.MOUSE_RELEASED
                && event.getEventType() != MouseEvent.MOUSE_CLICKED) return;
        logger.log(LogLevel.TRACE, LogCategory.INPUT, UiEventLogger.class, "input.mouse",
                () -> Map.of("type", event.getEventType().getName(), "button", event.getButton().name(),
                        "x", event.getSceneX(), "y", event.getSceneY()));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        scene.removeEventFilter(KeyEvent.ANY, keys);
        scene.removeEventFilter(MouseEvent.ANY, mouse);
        scene.getProperties().remove(REGISTRATION_KEY);
    }
}
