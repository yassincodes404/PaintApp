package com.paintstudio.app.config;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @Test void invalidDimensionsUseDefaults() {
        Properties values = new Properties();
        values.setProperty("window.width", "-1");
        values.setProperty("splash.height", "NaN");
        AppConfig config = ConfigLoader.load(values);
        assertEquals(1200, config.windowWidth());
        assertEquals(420, config.splash().height());
    }

    @Test void splashOverrideLeavesSharedValuesIntact() {
        var original = ConfigLoader.load(new Properties()).splash();
        var changed = original.withDuration(javafx.util.Duration.seconds(5));
        assertEquals(2000, original.duration().toMillis());
        assertEquals(5000, changed.duration().toMillis());
        assertEquals(original.animation(), changed.animation());
    }
}
