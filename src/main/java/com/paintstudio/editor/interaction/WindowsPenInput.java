package com.paintstudio.editor.interaction;

import static java.lang.foreign.ValueLayout.*;

import com.paintstudio.common.logging.*;
import com.paintstudio.editor.EditorView;
import com.paintstudio.ui.viewport.CanvasViewport.PenAction;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javafx.stage.Stage;

/** Receives Windows Ink on the Glass window's own UI thread. */
public final class WindowsPenInput implements AutoCloseable {
  private static final int UPDATE = 0x245, DOWN = 0x246, UP = 0x247, CAPTURE_CHANGED = 0x24c;
  private static final int DESTROY = 0x82, CANCEL = 0x1f, ACTIVATE = 6;
  private static final int TABLET_GESTURES = 0x02cc;
  private static final long DISABLE_INK_GESTURES = 0x00090001L;
  // 64-bit Windows ABI. POINTER_PEN_INFO embeds the 96-byte POINTER_INFO.
  private static final int PEN_SIZE = 120, FLAGS = 12, PIXEL = 32, HISTORY_COUNT = 68;
  private static final int PEN_FLAGS = 96, PEN_MASK = 100, PRESSURE = 104, HISTORY_LIMIT = 64;
  private static final FunctionDescriptor CALLBACK =
      FunctionDescriptor.of(
          JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
  private final Stage stage;
  private final EditorView editor;
  private final Arena arena = Arena.ofAuto();
  private final MemorySegment hwnd, callback;
  private final MemorySegment info = arena.allocate(PEN_SIZE, 8);
  private final MemorySegment history = arena.allocate((long) PEN_SIZE * HISTORY_LIMIT, 8);
  private final MemorySegment historyCount = arena.allocate(JAVA_INT);
  private final MemorySegment point = arena.allocate(8, 4);
  private int active = -1;
  private boolean installed;
  private boolean receivedPressure;
  private boolean receivedTilt;
  private double lastPressure = 1;
  private WinTabContext winTab;
  private boolean winTabContact, winTabOwnsStroke;
  private final String backend = System.getProperty("paintstudio.tablet.backend", "auto");

  public static WindowsPenInput attach(Stage stage, EditorView editor) {
    if (!System.getProperty("os.name", "").startsWith("Windows")
        || ADDRESS.byteSize() != 8
        || !Boolean.parseBoolean(System.getProperty("paintstudio.tablet.enabled", "true")))
      return null;
    try {
      var input = new WindowsPenInput(stage, editor);
      AppLogger.getInstance()
          .info(
              LogCategory.SYSTEM,
              WindowsPenInput.class,
              "tablet.bridge.ready",
              Map.of("backendPreference", input.backend, "winTabAvailable", input.winTab != null));
      return input;
    } catch (Throwable error) {
      AppLogger.getInstance()
          .error(LogCategory.SYSTEM, WindowsPenInput.class, "tablet.bridge.unavailable", error);
      return null;
    }
  }

  private WindowsPenInput(Stage stage, EditorView editor) throws Throwable {
    this.stage = stage;
    this.editor = editor;
    try (Arena temporary = Arena.ofConfined()) {
      MemorySegment title = temporary.allocateFrom(stage.getTitle(), StandardCharsets.UTF_16LE);
      MemorySegment candidate = MemorySegment.NULL;
      int thread = (int) Native.THREAD.invokeExact();
      do {
        candidate =
            (MemorySegment)
                Native.FIND_WINDOW.invokeExact(
                    MemorySegment.NULL, candidate, MemorySegment.NULL, title);
      } while (!candidate.equals(MemorySegment.NULL)
          && (int) Native.WINDOW_THREAD.invokeExact(candidate, MemorySegment.NULL) != thread);
      hwnd = candidate;
    }
    if (hwnd.equals(MemorySegment.NULL))
      throw new IllegalStateException("Could not find the application's native window");
    MethodHandle handler =
        MethodHandles.lookup()
            .findVirtual(WindowsPenInput.class, "message", CALLBACK.toMethodType())
            .bindTo(this);
    callback = Native.LINKER.upcallStub(handler, CALLBACK, arena);
    installed = (int) Native.INSTALL.invokeExact(hwnd, callback, 1L, 0L) != 0;
    if (!installed) throw new IllegalStateException("Windows rejected the pen input hook");
    openWinTab();
  }

  private void openWinTab() {
    if ("windows-ink".equals(backend)) return;
    try {
      winTab = WinTabContext.open(hwnd, arena);
      if (winTab != null) {
        winTab.focus(stage.isFocused());
        AppLogger.getInstance()
            .info(LogCategory.SYSTEM, WindowsPenInput.class, "tablet.wintab.ready", Map.of());
      }
    } catch (Throwable failure) {
      closeWinTab();
      AppLogger.getInstance()
          .error(LogCategory.SYSTEM, WindowsPenInput.class, "tablet.wintab.unavailable", failure);
    }
  }

  private void closeWinTab() {
    WinTabContext previous = winTab;
    winTab = null;
    if (previous != null) previous.close();
  }

  private void cancelContact() {
    if (active != -1 || winTabOwnsStroke) editor.viewport().cancel();
    active = -1;
    winTabOwnsStroke = false;
    winTabContact = false;
  }

  private long message(
      MemorySegment window, int message, long wparam, long lparam, long id, long data) {
    try {
      int pointer = (int) (wparam & 0xffff);
      if (message == TABLET_GESTURES && isDrawingArea(lparam)) return DISABLE_INK_GESTURES;
      if ((message == 0x20a || message == 0x20e) && isPromotedPen() && isDrawingArea(lparam))
        return 0;
      if (message == DESTROY) close();
      else if (message == ACTIVATE) {
        boolean focused = (wparam & 0xffff) != 0;
        if (!focused) cancelContact();
        if (winTab != null) winTab.focus(focused);
      } else if (message == CANCEL || (message == CAPTURE_CHANGED && pointer == active)) {
        cancelContact();
      } else if (winTab != null && message == WinTabContext.PACKET && winTab.matches(lparam)) {
        processWinTab(winTab.read((int) wparam));
        return 0;
      } else if (winTab != null
          && message == WinTabContext.PROXIMITY
          && winTab.matches(wparam)
          && (lparam & 0xffff) == 0) {
        if (winTabOwnsStroke) cancelContact();
        winTabContact = false;
      } else if (winTabOwnsStroke && (message == DOWN || message == UPDATE || message == UP)) {
        return 0;
      } else if (active != -1 && message >= 0x200 && message <= 0x20e && isPromotedPen()) {
        return 0; // Pen-promoted mouse message for a stroke already handled by Windows Ink.
      } else if (message == 0x201
          && active == -1
          && !winTabOwnsStroke
          && isPromotedPen()
          && editor.isPenSurface(
              (short) lparam / stage.getOutputScaleX(),
              (short) (lparam >>> 16) / stage.getOutputScaleY())) {
        editor.tabletReading(Double.NaN, Double.NaN, Double.NaN);
      } else if (message == DOWN
          && active == -1
          && (int) Native.PEN_INFO.invokeExact(pointer, info) != 0
          && (winTab == null
              || (!"wintab".equals(backend) && (info.get(JAVA_INT, PEN_MASK) & 1) != 0))
          && sample(info, true)) {
        active = pointer;
        return 0;
      } else if (pointer == active && (message == UPDATE || message == UP)) {
        boolean known = (int) Native.PEN_INFO.invokeExact(pointer, info) != 0;
        boolean cancelled = known && (info.get(JAVA_INT, FLAGS) & 0x8000) != 0;
        if (cancelled) editor.viewport().cancel();
        if (message == UP) {
          active = -1;
          if (!cancelled) editor.viewport().finishPen();
        } else if (known && !cancelled) deliverHistory(pointer);
        return 0;
      }
    } catch (Throwable error) {
      cancelContact();
      AppLogger.getInstance()
          .error(LogCategory.INPUT, WindowsPenInput.class, "tablet.input.failed", error);
    }
    try {
      return (long) Native.NEXT.invokeExact(window, message, wparam, lparam);
    } catch (Throwable error) {
      AppLogger.getInstance()
          .error(LogCategory.INPUT, WindowsPenInput.class, "tablet.dispatch.failed", error);
      return 0;
    }
  }

  private void deliverHistory(int pointer) throws Throwable {
    int count = info.get(JAVA_INT, HISTORY_COUNT);
    if (count > 1 && count <= HISTORY_LIMIT) {
      historyCount.set(JAVA_INT, 0, HISTORY_LIMIT);
      if ((int) Native.PEN_HISTORY.invokeExact(pointer, historyCount, history) != 0) {
        int returned = Math.min(HISTORY_LIMIT, historyCount.get(JAVA_INT, 0));
        for (int i = returned - 1; i >= 0; i--) {
          var entry = history.asSlice((long) i * PEN_SIZE, PEN_SIZE);
          if ((entry.get(JAVA_INT, FLAGS) & 4) != 0) sample(entry, false);
        }
        return;
      }
    }
    sample(info, false);
  }

  private boolean sample(MemorySegment value, boolean first) throws Throwable {
    int mask = value.get(JAVA_INT, PEN_MASK), flags = value.get(JAVA_INT, PEN_FLAGS);
    double rawPressure = (mask & 1) != 0 ? value.get(JAVA_INT, PRESSURE) / 1024.0 : Double.NaN;
    double tiltX = (mask & 4) != 0 ? value.get(JAVA_INT, 112) : Double.NaN;
    double tiltY = (mask & 8) != 0 ? value.get(JAVA_INT, 116) : Double.NaN;
    double rotation = (mask & 2) != 0 ? value.get(JAVA_INT, 108) : Double.NaN;
    if (first) lastPressure = 1;
    if (Double.isFinite(rawPressure)) lastPressure = rawPressure;
    return deliver(
        value.get(JAVA_INT, PIXEL),
        value.get(JAVA_INT, PIXEL + 4),
        lastPressure,
        rawPressure,
        tiltX,
        tiltY,
        rotation,
        first,
        action((flags & 1) != 0, (flags & 6) != 0),
        "Windows Ink");
  }

  private boolean isDrawingArea(long screenPoint) throws Throwable {
    point.set(JAVA_INT, 0, (short) screenPoint);
    point.set(JAVA_INT, 4, (short) (screenPoint >>> 16));
    if ((int) Native.TO_CLIENT.invokeExact(hwnd, point) == 0) return false;
    return editor.isPenSurface(
        point.get(JAVA_INT, 0) / stage.getOutputScaleX(),
        point.get(JAVA_INT, 4) / stage.getOutputScaleY());
  }

  private static boolean isPromotedPen() throws Throwable {
    return ((long) Native.MESSAGE_SOURCE.invokeExact() & 0xffffff80L) == 0xff515700L;
  }

  private void processWinTab(WinTabContext.Sample sample) throws Throwable {
    if (sample == null) return;
    boolean first = !winTabContact;
    winTabContact = sample.contact();
    if (!winTabContact) {
      if (winTabOwnsStroke) editor.viewport().finishPen();
      winTabOwnsStroke = false;
      return;
    }
    if (active != -1 || (!first && !winTabOwnsStroke)) return;
    winTabOwnsStroke =
        deliver(
            sample.x(),
            sample.y(),
            sample.pressure(),
            sample.pressure(),
            sample.tiltX(),
            sample.tiltY(),
            Double.NaN,
            first,
            action(sample.barrel(), sample.eraser()),
            "WinTab");
  }

  private static PenAction action(boolean barrel, boolean eraser) {
    if (eraser) return PenAction.ERASE;
    return barrel ? PenAction.PAN : PenAction.INK;
  }

  private boolean deliver(
      int screenX,
      int screenY,
      double pressure,
      double rawPressure,
      double tiltX,
      double tiltY,
      double rotation,
      boolean first,
      PenAction action,
      String backend)
      throws Throwable {
    point.set(JAVA_INT, 0, screenX);
    point.set(JAVA_INT, 4, screenY);
    if ((int) Native.TO_CLIENT.invokeExact(hwnd, point) == 0) return false;
    double x = point.get(JAVA_INT, 0) / stage.getOutputScaleX();
    double y = point.get(JAVA_INT, 4) / stage.getOutputScaleY();
    boolean accepted =
        editor.penSample(new InputSample(x, y, pressure, tiltX, tiltY, rotation), first, action);
    if (accepted) {
      editor.tabletReading(rawPressure, tiltX, tiltY);
      if (Double.isFinite(rawPressure) && !receivedPressure) {
        receivedPressure = true;
        AppLogger.getInstance()
            .info(
                LogCategory.SYSTEM,
                WindowsPenInput.class,
                "tablet.pressure.received",
                Map.of("backend", backend));
      }
      if ((Double.isFinite(tiltX) || Double.isFinite(tiltY)) && !receivedTilt) {
        receivedTilt = true;
        AppLogger.getInstance()
            .info(
                LogCategory.SYSTEM,
                WindowsPenInput.class,
                "tablet.tilt.received",
                Map.of("backend", backend));
      }
    }
    return accepted;
  }

  @Override
  public void close() {
    closeWinTab();
    if (!installed) return;
    try {
      if ((int) Native.REMOVE.invokeExact(hwnd, callback, 1L) != 0) installed = false;
    } catch (Throwable error) {
      AppLogger.getInstance()
          .error(LogCategory.INPUT, WindowsPenInput.class, "tablet.detach.failed", error);
    }
  }

  private static final class Native {
    static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER =
        SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup COMMON =
        SymbolLookup.libraryLookup("comctl32.dll", Arena.global());
    private static final SymbolLookup KERNEL =
        SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    static final MethodHandle FIND_WINDOW =
        call(USER, "FindWindowExW", ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    static final MethodHandle WINDOW_THREAD =
        call(USER, "GetWindowThreadProcessId", JAVA_INT, ADDRESS, ADDRESS);
    static final MethodHandle THREAD = call(KERNEL, "GetCurrentThreadId", JAVA_INT);
    static final MethodHandle INSTALL =
        call(COMMON, "SetWindowSubclass", JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
    static final MethodHandle REMOVE =
        call(COMMON, "RemoveWindowSubclass", JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
    static final MethodHandle NEXT =
        call(COMMON, "DefSubclassProc", JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
    static final MethodHandle PEN_INFO =
        call(USER, "GetPointerPenInfo", JAVA_INT, JAVA_INT, ADDRESS);
    static final MethodHandle PEN_HISTORY =
        call(USER, "GetPointerPenInfoHistory", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    static final MethodHandle MESSAGE_SOURCE = call(USER, "GetMessageExtraInfo", JAVA_LONG);
    static final MethodHandle TO_CLIENT = call(USER, "ScreenToClient", JAVA_INT, ADDRESS, ADDRESS);

    private static MethodHandle call(
        SymbolLookup dll, String name, MemoryLayout result, MemoryLayout... args) {
      return LINKER.downcallHandle(
          dll.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }
  }
}
