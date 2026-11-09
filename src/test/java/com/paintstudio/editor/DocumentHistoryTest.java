package com.paintstudio.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.document.model.*;
import com.paintstudio.drawing.model.*;
import com.paintstudio.editor.history.*;
import org.junit.jupiter.api.Test;

class DocumentHistoryTest {
  private final EditorConfig policy = EditorConfig.load();

  private DrawingDocument document() {
    return new DrawingDocument(policy.defaults(), policy);
  }

  private ShapeObject shape(String id, double x) {
    return new ShapeObject(id, ShapeType.RECTANGLE, x, 10, 30, 40, 0xff334455, 4);
  }

  private ObjectEdit add(DrawingDocument d, DrawingObject object) {
    return new ObjectEdit(
        "draw",
        0,
        d.objects(0).size(),
        null,
        object,
        d.canvas().bounds(),
        d.expandedFor(object, policy.defaults().canvas()));
  }

  @Test
  void expansionAndUndoPreserveCoordinatesAndSavedState() {
    var d = document();
    var original = d.canvas().bounds();
    var h = new CommandManager(d, 1, 10);
    var s = shape("one", -100);
    h.execute(add(d, s));
    assertTrue(d.canvas().bounds().minX() < 0);
    assertEquals(-100, s.x());
    assertTrue(d.dirty());
    h.undo();
    assertEquals(original, d.canvas().bounds());
    assertFalse(d.dirty());
    assertTrue(d.objects(0).isEmpty());
    h.redo();
    assertEquals(s, d.objects(0).getFirst());
    d.markSaved(d.revision());
    assertFalse(d.dirty());
    h.undo();
    assertTrue(d.dirty());
    h.redo();
    assertFalse(d.dirty());
  }

  @Test
  void savingSnapshotWhileEditingDoesNotMarkNewChangesSaved() {
    var d = document();
    var h = new CommandManager(d, 1, 10);
    h.execute(add(d, shape("one", 10)));
    var snapshot = d.snapshot();
    h.execute(add(d, shape("two", 100)));
    d.markSaved(snapshot.revision());
    assertTrue(d.dirty());
    assertEquals(1, snapshot.layers().getFirst().objects().size());
    h.undo();
    assertFalse(d.dirty());
  }

  @Test
  void newEditAfterUndoDropsRedoAndDoesNotReuseSavedRevision() {
    var d = document();
    var h = new CommandManager(d, 1, 10);
    h.execute(add(d, shape("one", 10)));
    d.markSaved(d.revision());
    h.undo();
    h.execute(add(d, shape("two", 20)));
    assertFalse(h.canRedo());
    assertTrue(d.dirty());
  }

  @Test
  void countBudgetAndOversizedEditsAreHandledBeforeMutation() {
    var d = document();
    var h = new CommandManager(d, 1, 2);
    for (int i = 0; i < 3; i++) h.execute(add(d, shape("s" + i, 10 + i)));
    h.undo();
    h.undo();
    assertFalse(h.canUndo());
    assertEquals(1, d.objects(0).size());
    long revision = d.revision();
    Command huge =
        new Command() {
          public void execute(DrawingDocument doc) {
            fail("Must not execute");
          }

          public void undo(DrawingDocument doc) {}

          public long estimatedBytes() {
            return 2 * 1024 * 1024;
          }

          public String name() {
            return "huge";
          }
        };
    assertThrows(IllegalArgumentException.class, () -> h.execute(huge));
    assertEquals(revision, d.revision());
  }

  @Test
  void modelRejectsInvalidGeometryAndStrokeStorageIsImmutable() {
    double[] points = {0, 0, 100, 100};
    var stroke = new StrokeObject("s", points, 0xff000000, 4);
    points[0] = 200;
    assertEquals(0, stroke.x(0));
    assertTrue(stroke.hit(50, 50, 1));
    assertFalse(stroke.hit(50, 90, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StrokeObject("bad", new double[] {Double.NaN, 0}, 0, 2));
    var d = document();
    var h = new CommandManager(d, 1, 2);
    assertThrows(
        IllegalArgumentException.class,
        () -> h.execute(add(d, shape("far", policy.coordinateLimit() + 100))));
    assertTrue(d.objects(0).isEmpty());
  }

  @Test
  void layerChangesUndoAndPreserveObjectOrder() {
    var d = document();
    var h = new CommandManager(d, 1, 10);
    h.execute(add(d, shape("one", 10)));
    var before = d.layers();
    var after = new java.util.ArrayList<>(before);
    after.add(new Layer("second", "Layer 2", true, java.util.List.of()));
    h.execute(new LayerEdit("add", before, after));
    assertEquals(2, d.layerCount());
    h.undo();
    assertEquals(1, d.layerCount());
    h.undo();
    assertTrue(d.objects(0).isEmpty());
    h.redo();
    h.redo();
    assertEquals("one", d.objects(0).getFirst().id());
    assertEquals(2, d.layerCount());
  }

  @Test
  void memoryBudgetIncludesRedoAndEvictsOldPayloads() {
    var d = document();
    var h = new CommandManager(d, 1, 100);
    double[] points = new double[32000];
    for (int i = 0; i < points.length; i++) points[i] = 100 + i * .01;
    for (int i = 0; i < 10; i++)
      h.execute(add(d, new StrokeObject("s" + i, points, 0xff000000, 3)));
    assertTrue(h.estimatedBytes() <= 1024 * 1024);
    long bytes = h.estimatedBytes();
    h.undo();
    assertEquals(bytes, h.estimatedBytes());
    int undone = 1;
    while (h.canUndo()) {
      h.undo();
      undone++;
    }
    assertTrue(undone < 10);
    assertEquals(10 - undone, d.objects(0).size());
  }
}
