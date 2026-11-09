# Paint Studio

A JavaFX desktop drawing editor with editable ink and shapes, layers, persistent
settings, undo/redo, project saving, PNG export, and bounded recovery storage.
Requires JDK 25 and Maven.

Set JAVA_HOME to JDK 25, then run the following command from the project root to open the editor.

    mvn javafx:run

The welcome screen offers New Document, Open Project, and recent projects.
In the editor, the expand icon or F11 hides the chrome. Escape restores it.
Settings controls whether to show the welcome screen, remember recent projects,
and use system full screen in focus mode. The recent limit, toolbar icon size,
and dark-canvas ink color are in src/main/resources/config/workspace.properties.

The pen rack offers Pen, Ballpoint, Fountain, Brush, Pencil, Highlighter, and
Calligraphy presets. On narrow windows they appear in the Ink presets menu.
The status bar shows the pressure and tilt reported by your pen. On 64-bit
Windows the app accepts Windows Ink and can use an installed WinTab driver.
To prefer WinTab, run `mvn "-Dpaintstudio.tablet.backend=wintab" javafx:run`.
Use `windows-ink` to select Windows Ink only. The default is `auto`.
To disable native tablet handling, pass `-Dpaintstudio.tablet.enabled=false`
to the application JVM.

Hold the native barrel button before touching down to pan. The inverted eraser
tip erases a stroke where it touches. Tablet buttons configured to send keys
can use B (brush), E (eraser), V (select), H (pan), Space (hold to pan),
Ctrl+Z/Ctrl+Y (undo/redo), and +/− (zoom). Wheel zoom requires Ctrl and is
paused during drawing. Tilt and supported pen rotation affect the nib shape.
Projects with oriented nibs use format version 3. Older project files still open.

The packages under src/main/java/com/paintstudio cover application startup, documents, drawing, editor interactions, UI, and shared diagnostics. Stylesheets and icons belong under src/main/resources. Test packages mirror the drawing, document, and editor domains.

PaintApplication is the entry point. See review/editor-implementation.md for the
implementation map, configuration reference, threading model, and limitations.
Some original scaffold files remain empty; the guide identifies the implemented classes.


## Application logging

One AppLogger instance accepts events from application components. A bounded queue
feeds one background writer. Logging starts during PaintApplication.init() and
drains during stop(). The default output is UTF-8 JSON Lines to both the console
and rotating files.

Bundled defaults are in src/main/resources/config/logging.properties.
On Windows, user overrides are read from
%LOCALAPPDATA%/PaintStudio/config/logging.properties. JVM properties such as
-Dpaintstudio.logging.enabled=false have highest precedence. Unknown and invalid
settings are reported when logging is enabled; invalid overrides retain the
previous valid value. The loader does not create a user configuration file.

Log files are written beneath %LOCALAPPDATA%/PaintStudio/logs. AppPaths resolves
config, logs, state, and recovery directories without creating them. Settings and
recovery services create their directories only when saving.

To disable logging, set logging.enabled=false in the user configuration before
launch. Disabled startup creates no logging worker, log directory, or log file.
Both destinations disabled also prevent the worker from starting.

For input diagnostics, enable logging.category.input=true and logging.level=DEBUG.
Shortcuts are then recorded. Individual keys require logging.input.key-events=true.
Mouse events require TRACE; movement additionally requires
logging.input.mouse-movement=true and is sampled at most every 100 milliseconds.
Typed text is disabled by default and controlled separately. PasswordField events
are excluded. These filters observe Paint Studio scenes only.

Runtime controls are AppLogger.getInstance().disable(), start(), and
reconfigure(LoggingConfigLoader.load().config()). Runtime changes are not
automatically persisted. close() is terminal. If a shutdown times out, state()
remains STOPPING until a later lifecycle call confirms the old worker has exited;
a second writer cannot overlap it.

Records contain a session ID, timestamp, originating thread, source, category,
event name, and copied context. Accepted application events have increasing
sequence numbers; internal dropped-event summaries use sequence zero. Context is
bounded and truncation is marked. Queue overload increments droppedEvents() and
emits a summary when output is available. A file-output failure is reported to
stderr; the console destination can continue. With no usable destination the
worker exits and state() becomes FAILED.

Startup records include validated logging settings with their origins, effective
application settings, configuration fallback diagnostics, an allowlist of runtime
details, resource outcomes, splash events, and time until the editor appears.
Log retention applies only to recognized closed Paint Studio log files. Active
files are protected by file locks, so simultaneous application runs may temporarily
exceed the configured retained file count.


## Single-instance startup

Paint Studio permits one running instance per OS user. InstanceManager acquires
an exclusive FileChannel lock on byte zero of
%LOCALAPPDATA%/PaintStudio/state/application.lock in Application.init(), before
logging, configuration loading, or the splash. A duplicate shows an OK-only
message and exits. A lock-check failure cancels startup.

The sole channel remains open until the JVM shutdown hook closes logging and
releases ownership. Process termination also releases OS locks. The lock file
is deliberately retained; its existence does not indicate ownership.
There is no application setting or launch flag to bypass this check.
This governs normal launches of this build, not modified binaries or
administrative interference.

Java documentation:
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html


## Shared appearance

ThemeManager applies shared base styles and the selected dark/light palette to
scene roots and dialog panes. Child controls inherit the palette. Call
ThemeManager.apply(scene) for each new window and ThemeManager.apply(dialogPane)
for dialogs. Existing registered roots update when setTheme("dark") or
setTheme("light") is called on the JavaFX Application Thread.

The Settings button opens appearance, canvas, and resource preferences.
Versioned preferences are stored at
%LOCALAPPDATA%/PaintStudio/config/settings.json. Existing preferences.properties
theme values migrate on startup. SettingsManager saves on the shared I/O worker
before applying live changes; ThemeManager only manages CSS.
Dark workspace background is #201F1E. The document background is independent.

The duplicate-instance message reads appearance and identity resources without
starting logging. It shares the themed header used by Settings and other
Paint Studio dialogs. Native file pickers follow the operating system appearance.


## Tests and startup diagnostics

Run fast unit tests with:

    mvn test

Run unit tests plus the real JavaFX startup/editor diagnostics scenario with:

    mvn -Pdiagnostics verify

The diagnostics profile requires a full JDK 25 with jcmd and a working graphical
desktop. It launches and closes a separate Paint Studio process. Its configuration,
logs, preferences, and instance lock are isolated beneath the run directory;
normal application data is not used. The test-only launcher samples JVM metrics
once per second and submits them through the real AppLogger as
diagnostics.jvm.sample events.

Each run writes target/diagnostics/<timestamp-and-id>/ containing:
- application.jsonl: application events plus JVM samples
- memory-samples.jsonl: extracted heap, non-heap, GC, thread, CPU-time samples
- startup.jfr: JFR profile recording from JVM startup, capped at 32 MiB
- jfr-event-types.txt: event types actually present in the recording
- threads.txt: a thread dump after application readiness
- heap-start.txt, heap-after-editor.txt and heap-idle.txt: heap snapshots
- native-baseline.txt, native-start.txt, and native-diff.txt: NMT baseline confirmation, initial category snapshot, and change
- application-console.log: merged child stdout/stderr, including startup failures
- run.properties and summary.txt: scenario metadata and successful-run result

The test waits for application.ready, draws 100 strokes through JavaFX input events,
exercises undo/redo, zoom, themes, settings-dialog construction, background
save/reopen and PNG export, and creates/releases 20 document models. It then observes
five seconds without scripted edits and requests normal JavaFX shutdown.
It checks process exit, startup/shutdown
events, at least three logged samples, and a parseable nonempty JFR recording.
The child loads JavaFX from named modules and enables native access for
javafx.graphics. The test checks for reported CSS errors, safe repeated logger
closure, and instance-lock release after both normal shutdown and an injected
initialization failure. The failure case has separate logs in the same run directory.
Raw outputs remain available on failure. Maven reports are in
target/surefire-reports and target/failsafe-reports.

These measurements include profiler overhead and the configured splash delay.
They establish a bounded startup/editor observation, not a performance budget or memory-leak
guarantee. Diagnostic artifacts are separate from production log retention.
Each run is retained for comparison; mvn clean removes target and these artifacts.
Copy recordings elsewhere before cleaning if they need to be kept.


The Maven property paintstudio.heap.max.mib defaults to 1024 and sets the
maximum Java heap for both javafx:run and the diagnostics child JVM. To try
another limit, run mvn -Pdiagnostics "-Dpaintstudio.heap.max.mib=768" verify.
The startup environment event and diagnostics summary record the effective
maximum. The limit applies to the Java heap.

The splash uses the configured display time. Startup reporting and editor icon
loading run in a background task while it is visible. Scene construction stays
on the JavaFX Application Thread. The editor opens after preparation and the
splash timer both finish. The diagnostics log records each milestone.
