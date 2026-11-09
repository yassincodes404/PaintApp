package com.paintstudio.editor.interaction;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/** Optional driver path for tablets configured to use WinTab instead of Windows Ink. */
final class WinTabContext implements AutoCloseable {
  static final int PACKET = 0x7ff0;
  static final int PROXIMITY = PACKET + 5;
  private static final int CONTEXT_SIZE = 172;
  private static final int STATUS = 0x0002,
      BUTTONS = 0x0040,
      X = 0x0080,
      Y = 0x0100,
      PRESSURE = 0x0400,
      ORIENTATION = 0x1000;
  private static final int REQUIRED = STATUS | BUTTONS | X | Y | PRESSURE;
  private static final int DEVICE_INFO = 100;
  private final MethodHandle close, enable, overlap, read;
  private final MemorySegment context, packet;
  private final int minPressure, maxPressure;
  private final boolean tilt;

  record Sample(
      int x,
      int y,
      double pressure,
      double tiltX,
      double tiltY,
      boolean contact,
      boolean barrel,
      boolean eraser) {}

  static WinTabContext open(MemorySegment window, Arena arena) throws Throwable {
    String windows = System.getenv("SystemRoot");
    if (windows == null) return null;
    Path path = Path.of(windows, "System32", "Wintab32.dll");
    if (!Files.isRegularFile(path)) return null;
    return new WinTabContext(SymbolLookup.libraryLookup(path, Arena.global()), window, arena);
  }

  private WinTabContext(SymbolLookup dll, MemorySegment window, Arena arena) throws Throwable {
    MethodHandle info = call(dll, "WTInfoA", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    MethodHandle open = call(dll, "WTOpenA", ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
    MethodHandle get = call(dll, "WTGetA", JAVA_INT, ADDRESS, ADDRESS);
    close = call(dll, "WTClose", JAVA_INT, ADDRESS);
    enable = call(dll, "WTEnable", JAVA_INT, ADDRESS, JAVA_INT);
    overlap = call(dll, "WTOverlap", JAVA_INT, ADDRESS, JAVA_INT);
    read = call(dll, "WTPacket", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    packet = arena.allocate(64, 4);
    MemorySegment config = arena.allocate(CONTEXT_SIZE, 4);
    if ((int) info.invokeExact(4, 0, MemorySegment.NULL) != CONTEXT_SIZE
        || (int) info.invokeExact(4, 0, config) != CONTEXT_SIZE)
      throw new IllegalStateException("WinTab system context is unavailable");
    int device = Math.max(0, config.get(JAVA_INT, 56));
    MemorySegment axes = arena.allocate(48, 4);
    if ((int) info.invokeExact(DEVICE_INFO + device, 15, MemorySegment.NULL) != 16
        || (int) info.invokeExact(DEVICE_INFO + device, 15, axes) != 16)
      throw new IllegalStateException("WinTab pressure range is unavailable");
    minPressure = axes.get(JAVA_INT, 0);
    maxPressure = axes.get(JAVA_INT, 4);
    if (maxPressure <= minPressure)
      throw new IllegalStateException("Invalid WinTab pressure range");
    tilt = (int) info.invokeExact(DEVICE_INFO + device, 17, MemorySegment.NULL) == 48;
    int fields = REQUIRED | (tilt ? ORIENTATION : 0);
    int left = config.get(JAVA_INT, 148), top = config.get(JAVA_INT, 152);
    int width = config.get(JAVA_INT, 156), height = config.get(JAVA_INT, 160);
    if (width <= 0 || height <= 0) throw new IllegalStateException("Invalid WinTab screen mapping");
    config.set(JAVA_INT, 40, config.get(JAVA_INT, 40) | 4); // CXO_MESSAGES
    config.set(JAVA_INT, 52, PACKET);
    config.set(JAVA_INT, 64, fields);
    config.set(JAVA_INT, 68, 0); // Absolute packet fields.
    config.set(JAVA_INT, 72, fields);
    config.set(JAVA_INT, 76, -1);
    config.set(JAVA_INT, 80, -1);
    config.set(JAVA_INT, 108, left);
    config.set(JAVA_INT, 112, top + height - 1);
    config.set(JAVA_INT, 120, width);
    config.set(JAVA_INT, 124, -height);
    context = (MemorySegment) open.invokeExact(window, config, 0);
    if (context.equals(MemorySegment.NULL))
      throw new IllegalStateException("WinTab context did not open");
    boolean valid = false;
    try {
      valid =
          (int) get.invokeExact(context, config) != 0
              && config.get(JAVA_INT, 64) == fields
              && config.get(JAVA_INT, 68) == 0
              && config.get(JAVA_INT, 52) == PACKET;
      if (!valid)
        throw new IllegalStateException("WinTab driver changed the requested packet layout");
    } finally {
      if (!valid) close();
    }
  }

  boolean matches(long handle) {
    return context.address() == handle;
  }

  Sample read(int serial) throws Throwable {
    if ((int) read.invokeExact(context, serial, packet) == 0) return null;
    int status = packet.get(JAVA_INT, 0), buttons = packet.get(JAVA_INT, 4);
    double pressure =
        Math.clamp(
            (packet.get(JAVA_INT, 16) - (double) minPressure)
                / (maxPressure - (double) minPressure),
            0,
            1);
    double tiltX = Double.NaN, tiltY = Double.NaN;
    if (tilt) {
      double azimuth = Math.toRadians(packet.get(JAVA_INT, 20) / 10.0);
      double lean = Math.clamp(90 - Math.abs(packet.get(JAVA_INT, 24) / 10.0), 0, 90);
      tiltX = Math.sin(azimuth) * lean;
      tiltY = -Math.cos(azimuth) * lean;
    }
    return new Sample(
        packet.get(JAVA_INT, 8),
        packet.get(JAVA_INT, 12),
        pressure,
        tiltX,
        tiltY,
        (status & 1) == 0 && ((buttons & 1) != 0 || pressure > 0),
        (buttons & 2) != 0,
        (status & 16) != 0);
  }

  void focus(boolean focused) throws Throwable {
    int enabled = (int) enable.invokeExact(context, focused ? 1 : 0);
    if (focused && enabled != 0) {
      int ignored = (int) overlap.invokeExact(context, 1);
    }
  }

  @Override
  public void close() {
    try {
      int ignored = (int) close.invokeExact(context);
    } catch (Throwable failure) {
      com.paintstudio.common.logging.AppLogger.getInstance()
          .error(
              com.paintstudio.common.logging.LogCategory.SYSTEM,
              WinTabContext.class,
              "tablet.wintab.close.failed",
              failure);
    }
  }

  private static MethodHandle call(
      SymbolLookup dll, String name, MemoryLayout result, MemoryLayout... args) {
    return Linker.nativeLinker()
        .downcallHandle(dll.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
  }
}
