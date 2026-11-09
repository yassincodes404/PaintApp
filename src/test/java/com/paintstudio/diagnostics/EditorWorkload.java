package com.paintstudio.diagnostics;

import com.paintstudio.app.*;
import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.common.concurrent.BackgroundJobs;
import com.paintstudio.common.logging.*;
import com.paintstudio.document.model.*;
import com.paintstudio.document.persistence.*;
import com.paintstudio.document.service.DocumentService;
import com.paintstudio.editor.EditorView;
import com.paintstudio.editor.state.ToolType;
import com.paintstudio.ui.viewport.CanvasViewport;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.input.*;
import javafx.stage.Window;

/** Real JavaFX input pipeline plus background save/export, run only by the diagnostics launcher. */
final class EditorWorkload {
  static void start() {
    Thread worker =
        new Thread(
            () -> {
              try {
                run();
              } catch (Throwable error) {
                AppLogger.getInstance()
                    .error(
                        LogCategory.PERFORMANCE,
                        EditorWorkload.class,
                        "diagnostics.editor.failed",
                        error);
                System.err.println("EDITOR_WORKLOAD_FAILED: " + error);
              }
            },
            "diagnostics-editor-workload");
    worker.setDaemon(true);
    worker.start();
  }

  private static void run() throws Exception {
    long started = System.nanoTime();
    EditorView editor =
        fx(
            () ->
                Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .map(w -> w.getScene().lookup("#paint-editor"))
                    .filter(EditorView.class::isInstance)
                    .map(EditorView.class::cast)
                    .findFirst()
                    .orElseThrow());
    fx(
        () -> {
          if (editor.document() != null || editor.lookup("#welcome-view") == null)
            throw new AssertionError("Startup did not show the welcome screen");
          Platform.runLater(
              () -> {
                Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .map(w -> w.getScene().lookup("#create-document"))
                    .filter(javafx.scene.control.Button.class::isInstance)
                    .map(javafx.scene.control.Button.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .fire();
              });
          ((javafx.scene.control.Button) editor.lookup("#welcome-new")).fire();
          if (editor.document() == null) throw new AssertionError("New document was not created");
          editor.applyCss();
          editor.layout();
          ((javafx.scene.control.Button) editor.lookup("#enter-focus")).fire();
          var chrome = editor.getScene().lookup(".window-header");
          if (chrome.isVisible()
              || editor.getTop() != null
              || editor.getBottom() != null
              || editor.getLeft() != null)
            throw new AssertionError("Focus mode did not hide the chrome");
          Event.fireEvent(
              editor,
              new KeyEvent(
                  KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
          if (!chrome.isVisible()
              || editor.getTop() == null
              || editor.getBottom() == null
              || editor.getLeft() == null)
            throw new AssertionError("Escape did not restore the editor controls");
          return null;
        });
    for (int batch = 0; batch < 10; batch++) {
      final int group = batch;
      fx(
          () -> {
            CanvasViewport view = editor.viewport();
            view.setTool(ToolType.BRUSH);
            for (int stroke = 0; stroke < 10; stroke++) {
              double y = 100 + group * 10 + stroke;
              mouse(view, MouseEvent.MOUSE_PRESSED, 200, y, true);
              for (int point = 1; point < 30; point++)
                mouse(
                    view,
                    MouseEvent.MOUSE_DRAGGED,
                    200 + point * 5,
                    y + Math.sin(point * .3) * 12,
                    true);
              mouse(view, MouseEvent.MOUSE_RELEASED, 345, y, false);
            }
            return null;
          });
      // Give JavaFX pulses an opportunity to render between input batches.
      Thread.sleep(25);
    }
    DocumentSnapshot snapshot =
        fx(
            () -> {
              if (editor.document().objects(0).size() != 100)
                throw new AssertionError("Drawing pipeline did not commit 100 strokes");
              var rack = (javafx.scene.layout.HBox) editor.lookup("#pen-presets");
              var fountain =
                  rack.getChildren().stream()
                      .filter(nib -> "Fountain pen".equals(nib.getAccessibleText()))
                      .map(javafx.scene.control.ToggleButton.class::cast)
                      .findFirst()
                      .orElseThrow();
              fountain.fire();
              var start = editor.viewport().localToScene(250, 220);
              double originalZoom = editor.viewport().zoom();
              scroll(editor.viewport(), false);
              if (editor.viewport().zoom() != originalZoom)
                throw new AssertionError("Unmodified wheel changed the canvas scale");
              if (!editor.penSample(
                  new com.paintstudio.editor.interaction.InputSample(
                      start.getX(), start.getY(), .1, 35, 0, Double.NaN),
                  true,
                  CanvasViewport.PenAction.INK))
                throw new AssertionError("Pen contact was not accepted");
              editor.penSample(
                  new com.paintstudio.editor.interaction.InputSample(
                      start.getX() + 80, start.getY(), .9, 0, 55, Double.NaN),
                  false,
                  CanvasViewport.PenAction.INK);
              scroll(editor.viewport(), true);
              if (editor.viewport().zoom() != originalZoom)
                throw new AssertionError("A wheel event zoomed while the pen was drawing");
              editor.viewport().finishPen();
              var penStroke =
                  (com.paintstudio.drawing.model.StrokeObject)
                      editor.document().objects(0).getLast();
              if (penStroke.pointCount() != 2 || penStroke.widthAt(0) >= penStroke.widthAt(1))
                throw new AssertionError(
                    "The pressure gesture did not produce a variable-width stroke");
              if (penStroke.angleAt(0) == penStroke.angleAt(1)
                  || penStroke.aspectAt(0) == penStroke.aspectAt(1))
                throw new AssertionError("Pen tilt did not change the nib geometry");
              editor.viewport().undo();
              for (int i = 0; i < 10; i++) editor.viewport().undo();
              for (int i = 0; i < 10; i++) editor.viewport().redo();
              editor.viewport().zoom(1.25);
              editor.viewport().center();
              ThemeManager.setTheme("light");
              editor.getScene().getRoot().applyCss();
              ThemeManager.setTheme("dark");
              editor.getScene().getRoot().applyCss();
              return editor.document().snapshot();
            });
    checkSettingsDialog(editor);
    Path output =
        Files.createDirectories(Path.of(System.getProperty("user.home"), "editor-workload"));
    var policy = EditorConfig.load();
    try (var jobs = new BackgroundJobs(4, 5)) {
      var service = new DocumentService(jobs, policy);
      Path project = output.resolve("drawing.pspaint");
      long revision = service.save(project, snapshot).get(10, TimeUnit.SECONDS);
      DocumentSnapshot loaded = service.open(project).get(10, TimeUnit.SECONDS);
      if (loaded.layers().getFirst().objects().size() != 100)
        throw new AssertionError("Saved objects lost");
      var bounds = fx(() -> editor.document().contentBounds().orElseThrow());
      jobs.submit(
              () -> {
                new PngExporter()
                    .export(
                        output.resolve("drawing.png"), loaded, bounds, policy.maxExportPixels());
                return null;
              })
          .get(10, TimeUnit.SECONDS);
      // Repeated model lifetimes exercise snapshot ownership and cleanup without retaining
      // references.
      for (int i = 0; i < 20; i++) {
        var doc = new DrawingDocument(loaded, policy);
        if (doc.objects(0).size() != 100) throw new AssertionError("Reopened model differs");
      }
      fx(
          () -> {
            editor.document().markSaved(revision);
            if (editor.document().dirty()) throw new AssertionError("Saved revision mismatch");
            AppLogger.getInstance()
                .info(
                    LogCategory.PERFORMANCE,
                    EditorWorkload.class,
                    "diagnostics.editor.completed",
                    Map.of(
                        "strokes",
                        100,
                        "pointsPerStroke",
                        30,
                        "reopenCycles",
                        20,
                        "renderedFrames",
                        editor.viewport().renderedFrames(),
                        "renderSubmissionNanos",
                        editor.viewport().totalRenderNanos(),
                        "elapsedMs",
                        (System.nanoTime() - started) / 1000000));
            return null;
          });
    }
    System.out.println("EDITOR_WORKLOAD_PASS");
  }

  private static void checkSettingsDialog(EditorView editor) throws Exception {
    CompletableFuture<Void> checked = new CompletableFuture<>();
    fx(
        () -> {
          Platform.runLater(
              () -> {
                javafx.stage.Stage dialog = null;
                try {
                  dialog =
                      Window.getWindows().stream()
                          .filter(javafx.stage.Stage.class::isInstance)
                          .map(javafx.stage.Stage.class::cast)
                          .filter(w -> w.getTitle().equals("Paint Studio settings"))
                          .findFirst()
                          .orElseThrow();
                  var pane = dialog.getScene().getRoot();
                  if (dialog.getStyle() != javafx.stage.StageStyle.UNDECORATED
                      || pane.lookup(".dialog-titlebar") == null)
                    throw new AssertionError("Settings lacks themed window chrome");
                  var tabs = (javafx.scene.control.ListView<?>) pane.lookup("#settings-sections");
                  if (tabs.getItems().size() != 3)
                    throw new AssertionError("Missing settings sections");
                  for (int i = 0; i < 3; i++) {
                    tabs.getSelectionModel().select(i);
                    pane.applyCss();
                    pane.layout();
                  }
                  checked.complete(null);
                } catch (Throwable error) {
                  checked.completeExceptionally(error);
                } finally {
                  if (dialog != null) dialog.hide();
                }
              });
          ((javafx.scene.control.Button) editor.lookup("#editor-settings")).fire();
          return null;
        });
    checked.get(5, TimeUnit.SECONDS);
  }

  private static void scroll(CanvasViewport view, boolean control) {
    Event.fireEvent(
        view,
        new ScrollEvent(
            ScrollEvent.SCROLL,
            250,
            220,
            250,
            220,
            false,
            control,
            false,
            false,
            false,
            false,
            0,
            40,
            0,
            40,
            ScrollEvent.HorizontalTextScrollUnits.NONE,
            0,
            ScrollEvent.VerticalTextScrollUnits.NONE,
            0,
            0,
            new PickResult(view, 250, 220)));
  }

  private static void mouse(
      CanvasViewport view,
      javafx.event.EventType<MouseEvent> type,
      double x,
      double y,
      boolean down) {
    Event.fireEvent(
        view,
        new MouseEvent(
            type,
            x,
            y,
            x,
            y,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            down,
            false,
            false,
            false,
            false,
            false,
            new PickResult(view, x, y)));
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            result.complete(action.call());
          } catch (Throwable error) {
            result.completeExceptionally(error);
          }
        });
    return result.get(10, TimeUnit.SECONDS);
  }
}
