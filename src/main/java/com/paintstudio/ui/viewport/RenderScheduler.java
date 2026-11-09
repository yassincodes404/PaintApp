package com.paintstudio.ui.viewport;

import javafx.animation.AnimationTimer;

/** Runs only while invalidated, coalescing input into a single pulse callback. */
public final class RenderScheduler extends AnimationTimer implements AutoCloseable {
  private final Runnable render;
  private boolean pending;

  public RenderScheduler(Runnable render) {
    this.render = render;
  }

  public void request() {
    if (!pending) {
      pending = true;
      start();
    }
  }

  @Override
  public void handle(long now) {
    stop();
    pending = false;
    render.run();
  }

  @Override
  public void close() {
    stop();
    pending = false;
  }
}
