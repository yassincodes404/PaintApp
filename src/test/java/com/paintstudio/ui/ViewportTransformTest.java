package com.paintstudio.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.paintstudio.drawing.geometry.Bounds;
import com.paintstudio.ui.viewport.ViewportTransform;
import org.junit.jupiter.api.Test;

class ViewportTransformTest {
  @Test
  void zoomKeepsPointerAnchoredAndClamps() {
    var t = new ViewportTransform();
    t.center(new Bounds(-100, -100, 4096, 4096), 1200, 800);
    double x = t.documentX(350), y = t.documentY(225);
    t.zoomAt(2, 350, 225, .05, 16);
    assertEquals(x, t.documentX(350), .000001);
    assertEquals(y, t.documentY(225), .000001);
    t.zoomAt(1000, 350, 225, .05, 16);
    assertEquals(16, t.zoom());
    t.pan(160, 320);
    assertEquals(x - 10, t.documentX(350), .000001);
    assertEquals(y - 20, t.documentY(225), .000001);
  }
}
