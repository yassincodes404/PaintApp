package com.paintstudio.document;

import static org.junit.jupiter.api.Assertions.*;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.document.model.*;
import com.paintstudio.document.persistence.*;
import com.paintstudio.drawing.model.*;
import com.paintstudio.settings.UserSettings;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPersistenceTest {
  @TempDir Path directory;
  private final EditorConfig policy = EditorConfig.load();

  private DocumentSnapshot sample() {
    var d = new DrawingDocument(policy.defaults(), policy);
    var stroke =
        new StrokeObject("stroke", new double[] {100, 100, 120, 130, 160, 100}, 0xff556677, 6);
    d.replace(0, 0, null, stroke, d.canvas().bounds());
    return d.snapshot();
  }

  @Test
  void roundTripPreservesEditableData() throws Exception {
    var file = directory.resolve("drawing.pspaint");
    var snapshot = sample();
    new ProjectWriter().write(file, snapshot, 1024 * 1024);
    var loaded = new ProjectReader(policy).read(file);
    assertEquals(snapshot.id(), loaded.id());
    assertEquals(snapshot.canvas(), loaded.canvas());
    var stroke = (StrokeObject) loaded.layers().getFirst().objects().getFirst();
    assertEquals(3, stroke.pointCount());
    assertEquals(120, stroke.x(1));
    assertEquals(0xff556677, stroke.argb());
  }

  @Test
  void pressureAndTranslucentNibSurviveSaveAndExport() throws Exception {
    var preset = com.paintstudio.drawing.tool.InkPreset.HIGHLIGHTER;
    var brush =
        new com.paintstudio.drawing.tool.BrushTool(
            new com.paintstudio.editor.interaction.InputSample(20, 20, .05),
            0xffffff00,
            20,
            100,
            .5,
            preset);
    brush.drag(new com.paintstudio.editor.interaction.InputSample(90, 20, 1, 30, 0, Double.NaN));
    brush.drag(new com.paintstudio.editor.interaction.InputSample(20, 20, 1, 0, 50, Double.NaN));
    StrokeObject original = (StrokeObject) brush.preview();
    assertTrue(original.widthAt(1) > original.widthAt(0));
    var bounds = new com.paintstudio.drawing.geometry.Bounds(0, 0, 120, 40);
    var snapshot =
        new DocumentSnapshot(
            "ink",
            0,
            new CanvasSettings(bounds, "#00000000"),
            List.of(new Layer("ink-layer", "Ink", true, List.of(original))));
    var project = directory.resolve("ink.pspaint");
    new ProjectWriter().write(project, snapshot, 100000);
    var loaded = new ProjectReader(policy).read(project);
    var stroke = (StrokeObject) loaded.layers().getFirst().objects().getFirst();
    assertEquals(original.widthAt(0), stroke.widthAt(0));
    assertEquals(original.widthAt(1), stroke.widthAt(1));
    assertEquals(preset.aspect, stroke.nibAspect());
    assertEquals(original.aspectAt(1), stroke.aspectAt(1));
    assertEquals(original.angleAt(2), stroke.angleAt(2));
    assertNotEquals(stroke.angleAt(1), stroke.angleAt(2));
    assertTrue(stroke.hit(50, 20, 0));
    var png = directory.resolve("ink.png");
    new PngExporter().export(png, loaded, bounds, 10000);
    var pixels = ImageIO.read(png.toFile());
    assertEquals(stroke.argb() >>> 24, pixels.getRGB(50, 20) >>> 24);
  }

  @Test
  void failedSavePreservesOldFile() throws Exception {
    var file = directory.resolve("drawing.pspaint");
    Files.writeString(file, "original");
    assertThrows(java.io.IOException.class, () -> new ProjectWriter().write(file, sample(), 10));
    assertEquals("original", Files.readString(file));
    try (var files = Files.list(directory)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void malformedUnknownVersionAndOversizedPointsAreRejected() throws Exception {
    var file = directory.resolve("drawing.pspaint");
    new ProjectWriter().write(file, sample(), 100000);
    String valid = Files.readString(file);
    Files.writeString(file, valid.replace("\"formatVersion\":1", "\"formatVersion\":99"));
    assertThrows(java.io.IOException.class, () -> new ProjectReader(policy).read(file));
    Files.writeString(file, valid + "{}");
    assertThrows(java.io.IOException.class, () -> new ProjectReader(policy).read(file));
    Files.writeString(file, valid.replace("\"thickness\":6.0", "\"thickness\":-1"));
    assertThrows(java.io.IOException.class, () -> new ProjectReader(policy).read(file));
    var tiny = new EditorConfig(policy.defaults(), 8, 10, 10, 10, 2, 1, 10000, 1000000, .75);
    Files.writeString(file, valid);
    assertThrows(java.io.IOException.class, () -> new ProjectReader(tiny).read(file));
  }

  @Test
  void recoveryRetentionAndPngExportAreBounded() throws Exception {
    var store = new RecoveryStore(directory.resolve("recovery"), policy);
    var p = new UserSettings.Performance(128, 1000, 120, 2, 1);
    for (int i = 0; i < 4; i++) store.save(sample(), p);
    assertEquals(2, store.candidates().size());
    var snapshot = sample();
    var bounds = snapshot.layers().getFirst().objects().getFirst().bounds();
    var file = directory.resolve("art.png");
    new PngExporter().export(file, snapshot, bounds, 10000);
    var image = ImageIO.read(file.toFile());
    assertEquals((int) Math.ceil(bounds.width()), image.getWidth());
    assertThrows(
        java.io.IOException.class,
        () -> new PngExporter().export(file, snapshot, snapshot.canvas().bounds(), 100));
    store.clear();
    assertTrue(store.candidates().isEmpty());
  }

  @Test
  void clearingOneDocumentPreservesAnotherDocumentsRecovery() throws Exception {
    var store = new RecoveryStore(directory.resolve("recovery"), policy);
    var first = sample();
    var second = sample();
    store.save(first, policy.defaults().performance());
    store.save(second, policy.defaults().performance());
    store.clearDocument(first.id());
    assertEquals(1, store.candidates().size());
    assertEquals(second.id(), new ProjectReader(policy).read(store.candidates().getFirst()).id());
  }
}
