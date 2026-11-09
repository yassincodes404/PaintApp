# Editor implementation — November 9, 2026

This is the implementation map for the theme, settings, and drawing-editor milestone.

## What is usable

- New expandable 4096 × 4096 documents; no blank raster backing image.
- Brush, rectangle, ellipse, single-object selection, move, bottom-right resize handle,
  recolor, and whole-object erasing. The eraser removes a clicked stroke or shape.
- Ctrl + wheel zoom anchored at the pointer. Middle-button drag or the Pan tool pans.
- Editable layers: add, raise/lower, show/hide. Hidden layers reject new drawing.
- Undo/redo for drawing, transformations, color, erasing, and layer changes.
- New/Open/Save/Save as; editable .pspaint JSON project files.
- PNG export of the full canvas or artwork bounds, with allocation checks.
- Settings with Apply, Cancel, Restore Defaults, and an Appearance, Canvas, and Resources sidebar.
- Dirty-document prompts on New/Open/Close.
- Periodic recovery of changed documents and a startup restore prompt.
- Shared structured logging and extended real-JavaFX diagnostics.

Shortcuts: Ctrl+N/O/S, Ctrl+Shift+S, Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z.
Escape cancels the active gesture. Delete removes the selected object.
Click Select and an object's outline to select it; drag to move or use its
bottom-right handle to resize. Recolor selected uses the current color picker value.

## Where the code lives

All paths below are relative to `src/main/java/com/paintstudio`.

| File / group | Responsibility |
|---|---|
| app/PaintApplication.java | Initializes policy, settings, shared worker, theme; constructs editor during splash; stops services |
| app/ThemeManager.java | Installs base, palette, component CSS; updates registered roots; no preference writes |
| app/config/EditorConfig.java | Loads and validates editor defaults and resource policy |
| settings/UserSettings.java | Immutable Appearance, Canvas, Performance groups with schema version |
| settings/SettingsValidator.java | Value ranges, supported themes, color/zoom validation |
| settings/SettingsStore.java | JSON persistence, missing-default merge, legacy theme migration, future-version protection, backup |
| settings/SettingsManager.java | Background save, effective settings, UI-dispatched notifications |
| common/concurrent/BackgroundJobs.java | One lazy I/O worker, bounded admission, cancellation, shutdown |
| common/io/AtomicFiles.java | Temporary-file writes, force, replacement, fallback backup |
| common/io/LimitedInputStream.java | Streaming byte bound even if a file grows during reading |
| document/model/CanvasSettings.java | Bounds and document background |
| document/model/DocumentSnapshot.java | Immutable document ID, revision, canvas settings, ordered layers |
| document/model/DrawingDocument.java | Owner-thread editing, IDs, resource validation, expansion, dirty/saved state |
| document/model/Layer.java | Immutable layer ID/name/visibility and object references |
| drawing/model/DrawingObject.java | Common editable-object interface |
| drawing/model/StrokeObject.java | Defensive primitive-array storage, bounds, hit testing, transforms, color |
| drawing/model/ShapeObject.java, ShapeType.java | Rectangle/ellipse geometry and object operations |
| drawing/geometry/Bounds.java, GeometryUtils.java | Bounds operations and segment distance |
| drawing/tool/DrawingTool.java, BrushTool.java, ShapeTool.java | Gesture construction independent of JavaFX mouse events |
| editor/interaction/InputSample.java | Device-neutral input sample; pressure reserved for a future adapter |
| editor/state/ToolType.java | Supported tools |
| drawing/renderer/DrawingRenderer.java | JavaFX drawing commands for model objects |
| ui/viewport/ViewportTransform.java | Screen/document mapping, anchored zoom, pan |
| ui/viewport/RenderScheduler.java | Coalesces invalidations; stops when no redraw is pending |
| ui/viewport/CanvasViewport.java | Three screen-sized surfaces, culling, input, previews, selection; CSS-backed overlay colors |
| editor/history/Command.java, ObjectEdit.java, LayerEdit.java | Reversible edits with conservative retained-byte estimates |
| editor/history/CommandManager.java | Undo/redo admission, eviction, state restoration, action logging |
| document/persistence/ProjectReader.java | Strict streaming parsing; schema/field/count/geometry validation |
| document/persistence/ProjectWriter.java | Streaming editable project encoding with output-size limit |
| document/persistence/PngExporter.java | Background Java2D rasterization and PNG encoding from immutable model |
| document/persistence/RecoveryStore.java | Bounded recovery generations; document-specific cleanup |
| document/service/DocumentService.java | Asynchronous open/save and duration logging |
| editor/EditorView.java | Editor controls, file operations, settings integration, autosave and unsaved prompts |
| ui/settings/SettingsView.java | Validated settings form; successful persistence precedes live application |

Existing empty scaffold files (such as MouseHandler, SelectionManager, EraserTool)
are not alternative implementations. Their current interaction responsibilities
are in CanvasViewport; no unused service instances or threads are created.

Additional files:
- src/main/resources/config/editor.properties: defaults and policy.
- src/main/resources/css/{dark,light,components}.css: semantic palette and controls.
- pom.xml: Gson 2.13.2 for JSON. Existing Java 25/JavaFX and 1024 MiB launcher cap retained.
- README.md: current usage and diagnostics workflow.
- Tests listed below.

## Ownership and threading

The live document, command stacks, controls, and attached canvases are owned by
the JavaFX Application Thread. Worker jobs do not edit them.

Startup reads the small settings file before showing the splash so the splash
preference and theme are known. Existing background startup preparation continues
while the splash is visible. Scene/editor construction remains on the FX thread.

The shared I/O executor has zero eager threads, at most one worker, an eight-job
queue, and a 20-second idle expiry. Queue overload fails explicitly instead of
executing disk I/O on the caller. It handles settings/project/recovery writes,
project reads, and PNG rasterization/encoding. Futures support cancellation;
long object loops check interruption. Shutdown waits up to the configured deadline
then interrupts remaining work. Cancellation cannot guarantee immediate interruption
inside filesystem or PNG-library calls.

The logger retains its existing separate bounded queue and worker. Autosave uses a
JavaFX Timeline, not a polling worker; setting its interval to 0 removes the timer.
Rendering uses AnimationTimer only while a redraw is pending.

Snapshot capture copies layer/object-reference lists on the FX thread. Objects and
their point arrays are immutable, so the background writer can share their data.
An older save completing after new edits marks only its captured revision saved.
Undo/redo restores content revision identifiers, so returning to the saved state
correctly clears the dirty marker.

## Configuration reference

Application identity, theme.default and splash master settings remain in
src/main/resources-filtered/config/app.properties.
Editor defaults/policy live in src/main/resources/config/editor.properties.
Settings are stored under AppPaths.config(), currently
%LOCALAPPDATA%/PaintStudio/config on Windows, with the existing ~/.paintstudio
fallback elsewhere. This milestone preserves these existing paths; it does not
introduce XDG/macOS path migrations.

| Key | Default | Range / behavior |
|---|---:|---|
| canvas.grid | false | Boolean; live preference |
| canvas.width / canvas.height | 4096 | 256–32768; new documents only |
| canvas.background | #FFFFFF | #RRGGBB or #RRGGBBAA; new documents only |
| canvas.auto-expand | true | Boolean; live interaction policy |
| canvas.expansion.step | 512 | 64–4096 document units |
| canvas.expansion.margin | 128 | 0 through expansion step |
| canvas.zoom.min / max | 0.05 / 16 | Min 0.01–1; max 1–64 |
| history.memory.mib | 128 | 1–256 MiB; undo and redo combined |
| history.commands | 1000 | 1–10000; undo and redo combined |
| recovery.interval.seconds | 120 | 0 disables; otherwise 15–3600 |
| recovery.generations | 3 | 1–10 retained generations globally |
| recovery.max.mib | 128 | 1–512 MiB disk budget globally |
| io.queue.capacity | 8 | 1–64; restart required |
| io.shutdown.seconds | 10 | 1–60; restart required |
| document.max.objects | 100000 | 1–1000000; restart required |
| document.max.points | 2000000 | 1–4000000; restart required |
| stroke.max.points | 20000 | 2 through document point limit |
| project.max.mib | 128 | 1–128; read/write byte limit |
| export.max.pixels | 16777216 | 1–32000000; permits default 4096² export |
| document.coordinate.limit | 1000000 | 32768–10000000; absolute positive/negative limit |
| input.spacing | 0.75 | 0.1–10 screen pixels between accepted samples |

User settings JSON stores these under appearance/canvas/performance field names
defined by UserSettings. The UI exposes the common preferences; zoom bounds are
available in JSON. Policy limits are application configuration, not a preference slider.
Bundled policy changes require restarting/rebuilding. Invalid resource policy
fails initialization; invalid user settings fall back to defaults with a warning.

The default theme is inherited from app.properties; a saved theme overrides it.
The user splash preference can turn the splash off but cannot override an
application-level splash.enabled=false.

Missing JSON fields inherit defaults. Invalid saved settings preserve the input
file and use defaults. schemaVersion other than 1 makes the store read-only;
Apply reports a failure instead of overwriting a newer file.
Existing preferences.properties is retained after migration.
Explicit writes preserve settings.json.bak before replacing settings.json.
Unknown keys in the current schema are not guaranteed to survive a subsequent save.

The Maven property paintstudio.heap.max.mib=1024 controls -Xmx for javafx:run and
the diagnostics child. It is not a live preference and does not cap native/GPU or
total process memory. Alternative launchers must supply their own JVM options.

## Persistence, recovery, and failure behavior

.pspaint is a UTF-8 JSON format with formatVersion=1, document ID/revision,
background, workspace bounds, and ordered layers with object IDs and geometry.
Undo history and transient selection are not persisted.
Unknown project fields/versions are rejected rather than partially interpreted.

Writers create a temporary file next to the destination and force its contents
before replacement. If atomic moves are unavailable, an existing destination is
backed up before a non-atomic replacement. That fallback is not a crash-atomic
guarantee, and no filesystem operation can promise survival of every hardware failure.
An encoding/size-limit failure before replacement leaves the old file intact.

Recovery runs only when the current document is dirty and its revision changed.
It skips overlapping file/recovery jobs. A recovery failure is logged and shown
in the status bar; ordinary saving remains available.
Successful saves and deliberate closing/discarding clean only that document's
recovery, preserving unrelated earlier work. The retention count/byte policy
still evicts oldest generations globally. The startup prompt offers the newest
remaining generation. Older files remain inspectable/openable as .pspaint projects.

An autosave is not a substitute for Save. A crash before the first configured
interval can lose changes since the last manual save.
PNG is an exported image; reopen .pspaint to retain editable objects.

## Memory and rendering

Empty canvas area is coordinates and a background color, not allocated pixels.
Three viewport-sized JavaFX canvases hold content, preview, and selection.
A model edit redraws visible content; live brush updates only redraw preview.
Off-screen objects are culled using bounds, with a linear scan for now.
Selection hit testing is also linear. No unbounded spatial cache exists.

History counts conservative estimated retained bytes, not exact JVM heap usage.
A command larger than its budget is rejected before modifying the model.
Oldest commands are evicted when count/byte limits are reached.
Closed/replaced document references are released; Java/GPU reclamation timing is
controlled by their runtimes rather than forced System.gc() calls.

PNG export uses a bounded full-resolution BufferedImage on the I/O worker.
Java2D and JavaFX antialiasing can differ slightly.
Large/tiled raster import, textured brushes, pressure-sensitive input, lasso or
multi-object selection, regional PNG selection, and advanced spatial indexing
are intentionally outside this milestone. No promise of unlimited canvas memory
or constant-time rendering is made.

## Tests and diagnostics

Run:
    mvn test
    mvn -Pdiagnostics verify

Unit tests under src/test/java/com/paintstudio cover:
- SettingsStoreTest: migration, missing defaults, corruption preservation,
  future-schema protection, failed writes, round trips and backup.
- DocumentHistoryTest: expansion/undo, saved state, snapshot/save races, redo
  invalidation, count and byte budgets, geometry, layer operations.
- ProjectPersistenceTest: editable round trips, failed-save preservation,
  invalid versions/geometry/point limits, PNG allocation limit, recovery retention
  and document-specific cleanup.
- ViewportTransformTest: pointer-anchored zoom, clamping and pan.
- BackgroundJobsTest: bounded queue, background execution and cancellation.
- Existing ConfigLoaderTest remains active.

EditorWorkload drives real JavaFX mouse events for 100 strokes, undo/redo,
zoom, CSS theme changes, settings dialog construction, asynchronous project
save/reopen, PNG output, and 20 document model lifetimes.
StartupDiagnosticsIT retains JFR/NMT/heap/thread samples and checks the 1 GiB
heap cap, CSS errors, readiness sequencing, clean shutdown and lock release.
Render statistics measure time submitting JavaFX drawing commands, not GPU frame
completion or end-to-end input latency. This workload is not a proof of no leaks.

Diagnostics use isolated application data beneath target/diagnostics.
Artifacts are retained for inspection until mvn clean; production log rotation
does not remove diagnostic artifacts.

## Documentation used

- [JavaFX canvas threading](https://openjfx.io/javadoc/25/javafx.graphics/javafx/scene/canvas/GraphicsContext.html)
- [JavaFX Task and immutable worker input](https://openjfx.io/javadoc/25/javafx.graphics/javafx/concurrent/Task.html)
- [JavaFX CSS-backed custom properties](https://openjfx.io/javadoc/25/javafx.graphics/javafx/css/StyleablePropertyFactory.html)
- [Java file replacement semantics](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html)
- [Gson documentation](https://github.com/google/gson/blob/main/UserGuide.md)
