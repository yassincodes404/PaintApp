package com.paintstudio.editor;

import com.paintstudio.app.*;
import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.app.config.WorkspaceConfig;
import com.paintstudio.common.concurrent.BackgroundJobs;
import com.paintstudio.common.logging.*;
import com.paintstudio.document.model.*;
import com.paintstudio.document.persistence.*;
import com.paintstudio.document.service.DocumentService;
import com.paintstudio.drawing.tool.InkPreset;
import com.paintstudio.editor.history.*;
import com.paintstudio.editor.interaction.InputSample;
import com.paintstudio.editor.state.ToolType;
import com.paintstudio.settings.*;
import com.paintstudio.ui.component.PenNib;
import com.paintstudio.ui.component.WorkspaceIcon;
import com.paintstudio.ui.component.WorkspaceIcon.Kind;
import com.paintstudio.ui.dialog.NewDocumentDialog;
import com.paintstudio.ui.dialog.ThemedDialogs;
import com.paintstudio.ui.settings.SettingsView;
import com.paintstudio.ui.viewport.CanvasViewport;
import com.paintstudio.ui.welcome.WelcomeView;
import java.nio.file.Path;
import java.util.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javafx.util.Duration;

/** Coordinates services; model and rendering are independent of file dialogs. */
public final class EditorView extends BorderPane implements AutoCloseable {
  private final Stage stage;
  private final SettingsManager settings;
  private final EditorConfig policy;
  private final BackgroundJobs jobs;
  private final DocumentService service;
  private final RecoveryStore recovery;
  private final CanvasViewport viewport;
  private final WorkspaceConfig workspace;
  private final RecentProjectsStore recents;
  private List<RecentProjectsStore.Entry> recentEntries = List.of();
  private final BooleanProperty focusMode = new SimpleBooleanProperty();
  private boolean home, focusFullScreen;
  private boolean rememberingRecent;
  private final HBox toolbar;
  private final VBox toolRail;
  private final HBox statusBar;
  private final StackPane canvas;
  private final HBox focusControls;
  private final ColorPicker color;
  private final Label status = new Label(), notice = new Label("Ready");
  private final Button undo = new Button("Undo"), redo = new Button("Redo");
  private int activeLayer;
  private InkPreset selectedPreset = InkPreset.PEN;
  private DrawingDocument document;
  private CommandManager history;
  private Path path;
  private boolean busy, closing, recovering;
  private Timeline autosave;
  private long recoveredRevision = -1;

  public EditorView(
      Stage stage,
      SettingsManager settings,
      EditorConfig policy,
      WorkspaceConfig workspace,
      BackgroundJobs jobs) {
    this.stage = stage;
    this.settings = settings;
    this.policy = policy;
    this.workspace = workspace;
    this.jobs = jobs;
    recents = new RecentProjectsStore(AppPaths.state(), workspace.recentLimit());
    service = new DocumentService(jobs, policy);
    recovery = new RecoveryStore(AppPaths.recovery(), policy);
    viewport = new CanvasViewport(policy, settings.current());
    viewport.onChanged(this::refresh);
    viewport.onMessage(this::message);
    setId("paint-editor");
    Button exitFocus = icon(Kind.COLLAPSE, "Exit canvas focus (Esc)", () -> setFocusMode(false));
    exitFocus.setId("exit-focus");
    focusControls =
        new HBox(6, icon(Kind.ZOOM_IN, "Zoom in", () -> viewport.zoom(1.25)), exitFocus);
    focusControls.getStyleClass().add("focus-controls");
    focusControls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    StackPane.setAlignment(focusControls, Pos.TOP_RIGHT);
    StackPane.setMargin(focusControls, new Insets(14));
    focusControls.visibleProperty().bind(focusMode);
    focusControls.managedProperty().bind(focusMode);
    canvas = new StackPane(viewport, focusControls);
    Runnable showSettings = () -> SettingsView.show(stage, settings, policy.defaults());
    WorkspaceIcon.decorate(undo, Kind.UNDO, "Undo (Ctrl+Z)", workspace.iconSize());
    WorkspaceIcon.decorate(redo, Kind.REDO, "Redo (Ctrl+Y)", workspace.iconSize());
    undo.setOnAction(e -> viewport.undo());
    redo.setOnAction(e -> viewport.redo());
    Button preferences = icon(Kind.SETTINGS, "Settings", showSettings);
    preferences.setId("editor-settings");
    Button enterFocus = icon(Kind.EXPAND, "Canvas focus (F11)", () -> setFocusMode(true));
    enterFocus.setId("enter-focus");

    Menu file =
        new Menu(
            "File",
            null,
            item("New document", this::newDocument),
            item("Open project", this::open),
            new SeparatorMenuItem(),
            item("Save", () -> save(false, () -> {})),
            item("Save as…", () -> save(true, () -> {})),
            item("Export PNG…", this::export));
    MenuItem undoItem = item("Undo", viewport::undo);
    MenuItem redoItem = item("Redo", viewport::redo);
    undoItem.disableProperty().bind(undo.disableProperty());
    redoItem.disableProperty().bind(redo.disableProperty());
    Menu edit =
        new Menu(
            "Edit",
            null,
            undoItem,
            redoItem,
            new SeparatorMenuItem(),
            item("Settings…", showSettings));
    Menu view =
        new Menu(
            "View",
            null,
            item("Canvas focus", () -> setFocusMode(true)),
            item("Zoom in", () -> viewport.zoom(1.25)),
            item("Zoom out", () -> viewport.zoom(.8)),
            item("Center canvas", viewport::center));
    MenuBar menus = new MenuBar(file, edit, layerMenu(), view);
    menus.setMinWidth(Region.USE_PREF_SIZE);
    menus.getStyleClass().add("editor-menus");
    toolRail = new VBox(3);
    toolRail.setId("tool-rail");
    toolRail.getStyleClass().add("tool-rail");
    ToggleGroup group = new ToggleGroup();
    for (ToolType type : ToolType.values()) {
      ToggleButton b =
          new ToggleButton(
              switch (type) {
                case BRUSH -> "Brush";
                case RECTANGLE -> "Rectangle";
                case ELLIPSE -> "Ellipse";
                case SELECT -> "Select";
                case ERASER -> "Erase stroke";
                case PAN -> "Pan";
              });
      WorkspaceIcon.decorate(b, Kind.valueOf(type.name()), b.getText(), workspace.iconSize());
      b.setToggleGroup(group);
      b.setOnAction(
          e -> {
            if (b.isSelected()) viewport.setTool(type);
            else b.setSelected(true);
          });
      if (type == ToolType.BRUSH) b.setSelected(true);
      toolRail.getChildren().add(b);
    }
    color = new ColorPicker(Color.web(workspace.defaultInk()));
    color.setOnAction(e -> viewport.setInk(color.getValue()));
    Spinner<Integer> width = new Spinner<>(1, 128, InkPreset.PEN.width);
    viewport.setThickness(InkPreset.PEN.width);
    width.setPrefWidth(60);
    width.setAccessibleText("Stroke width");
    width.setTooltip(new Tooltip("Stroke width in pixels"));
    width.valueProperty().addListener((o, a, b) -> viewport.setThickness(b));
    color.getStyleClass().add("ink-swatch");
    color.setAccessibleText("Ink color");
    color.setTooltip(new Tooltip("Ink color"));
    ToggleGroup nibs = new ToggleGroup();
    MenuButton penMenu = new MenuButton();
    penMenu.setAccessibleText("Ink presets");
    penMenu.setTooltip(new Tooltip("Ink presets"));
    penMenu.setGraphic(new PenNib(InkPreset.PEN));
    penMenu.getStyleClass().add("pen-menu");
    HBox penRack = new HBox(1);
    penRack.setId("pen-presets");
    penRack.getStyleClass().add("pen-rack");
    for (InkPreset preset : InkPreset.values()) {
      ToggleButton nib = new ToggleButton("", new PenNib(preset));
      nib.setToggleGroup(nibs);
      nib.setAccessibleText(preset.label);
      nib.setTooltip(new Tooltip(preset.label));
      nib.getStyleClass().add("pen-preset");
      Runnable choose =
          () -> {
            nib.setSelected(true);
            group.getToggles().getFirst().setSelected(true);
            viewport.setTool(ToolType.BRUSH);
            selectedPreset = preset;
            viewport.setInkPreset(preset);
            Color ink = preset == InkPreset.PEN ? defaultInk(document) : Color.web(preset.color);
            color.setValue(ink);
            viewport.setInk(ink);
            width.getValueFactory().setValue(preset.width);
            penMenu.setGraphic(new PenNib(preset));
            AppLogger.getInstance()
                .info(
                    LogCategory.ACTION,
                    EditorView.class,
                    "ink.preset.selected",
                    Map.of("preset", preset.name()));
          };
      nib.setOnAction(event -> choose.run());
      nib.setSelected(preset == InkPreset.PEN);
      penRack.getChildren().add(nib);
      MenuItem option = item(preset.label, choose);
      option.setGraphic(new PenNib(preset));
      penMenu.getItems().add(option);
    }
    var wide = widthProperty().greaterThanOrEqualTo(800);
    penRack.visibleProperty().bind(wide);
    penRack.managedProperty().bind(wide);
    penMenu.visibleProperty().bind(wide.not());
    penMenu.managedProperty().bind(wide.not());
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    toolbar =
        new HBox(
            4,
            icon(Kind.HOME, "Welcome screen", this::showHome),
            menus,
            spacer,
            penRack,
            penMenu,
            color,
            width,
            icon(Kind.RECOLOR, "Recolor selected", viewport::recolorSelection),
            undo,
            redo,
            preferences,
            enterFocus);
    toolbar.setId("editor-command-bar");
    toolbar.getStyleClass().add("editor-command-bar");
    statusBar =
        new HBox(
            6,
            status,
            notice,
            icon(Kind.ZOOM_OUT, "Zoom out", () -> viewport.zoom(.8)),
            icon(Kind.ZOOM_IN, "Zoom in", () -> viewport.zoom(1.25)),
            icon(Kind.CENTER, "Center canvas", viewport::center));
    HBox.setHgrow(notice, Priority.ALWAYS);
    statusBar.getStyleClass().add("status-bar");
    if (settings.current().appearance().showWelcome()) showHome();
    else install(new DrawingDocument(settings.current(), policy), null);
    if (settings.current().appearance().rememberRecent()) {
      jobs.submit(recents::load)
          .thenAccept(entries -> Platform.runLater(() -> updateRecent(entries)));
    }
    stage
        .fullScreenProperty()
        .addListener(
            (o, old, current) -> {
              if (!current && focusFullScreen) setFocusMode(false);
            });
    configure(settings.current());
    settings.listen(this::configure);
    addEventFilter(
        KeyEvent.KEY_PRESSED,
        e -> {
          if (e.getCode() == KeyCode.F11 && !home) {
            setFocusMode(!focusMode.get());
            e.consume();
            return;
          }
          if (e.getCode() == KeyCode.ESCAPE && focusMode.get()) {
            setFocusMode(false);
            e.consume();
            return;
          }
          if (e.getTarget() instanceof TextInputControl) return;
          if (!home && !e.isShortcutDown() && !e.isAltDown()) {
            if (e.getCode() == KeyCode.SPACE) {
              viewport.setSpacePan(true);
              e.consume();
              return;
            }
            ToolType shortcut =
                switch (e.getCode()) {
                  case B -> ToolType.BRUSH;
                  case E -> ToolType.ERASER;
                  case V -> ToolType.SELECT;
                  case H -> ToolType.PAN;
                  default -> null;
                };
            if (shortcut != null && !viewport.drawingGestureActive()) {
              group.getToggles().get(shortcut.ordinal()).setSelected(true);
              viewport.setTool(shortcut);
              e.consume();
              return;
            }
            switch (e.getCode()) {
              case ADD, PLUS, EQUALS -> viewport.zoom(1.25);
              case SUBTRACT, MINUS -> viewport.zoom(.8);
              default -> {}
            }
          }
          if (home && e.getCode() != KeyCode.N && e.getCode() != KeyCode.O) return;
          if (e.isShortcutDown()) {
            switch (e.getCode()) {
              case S -> save(e.isShiftDown(), () -> {});
              case O -> open();
              case N -> newDocument();
              case Z -> {
                if (e.isShiftDown()) viewport.redo();
                else viewport.undo();
              }
              case Y -> viewport.redo();
              default -> {
                return;
              }
            }
            e.consume();
          }
        });
    addEventFilter(
        KeyEvent.KEY_RELEASED,
        event -> {
          if (event.getCode() == KeyCode.SPACE) viewport.setSpacePan(false);
        });
    stage
        .focusedProperty()
        .addListener(
            (observable, old, focused) -> {
              if (!focused) viewport.setSpacePan(false);
            });
  }

  public DrawingDocument document() {
    return document;
  }

  public CommandManager history() {
    return history;
  }

  public CanvasViewport viewport() {
    return viewport;
  }

  private Button icon(Kind kind, String label, Runnable action) {
    Button button = WorkspaceIcon.decorate(new Button(), kind, label, workspace.iconSize());
    button.setOnAction(e -> action.run());
    return button;
  }

  public ReadOnlyBooleanProperty focusModeProperty() {
    return focusMode;
  }

  private void setFocusMode(boolean active) {
    if (home || active == focusMode.get()) return;
    viewport.cancel();
    focusMode.set(active);
    setTop(active ? null : toolbar);
    setLeft(active ? null : toolRail);
    setBottom(active ? null : statusBar);
    if (active && settings.current().appearance().fullScreenFocus()) {
      focusFullScreen = true;
      stage.setFullScreenExitHint("");
      stage.setFullScreen(true);
    } else if (!active && focusFullScreen) {
      focusFullScreen = false;
      stage.setFullScreen(false);
    }
    viewport.requestFocus();
    AppLogger.getInstance()
        .info(LogCategory.ACTION, EditorView.class, "workspace.focus", Map.of("active", active));
  }

  private void showHome() {
    if (busy) return;
    if (focusMode.get()) setFocusMode(false);
    viewport.cancel();
    home = true;
    setTop(null);
    setLeft(null);
    setBottom(null);
    stage.setTitle(AppInfo.getName());
    setCenter(
        new WelcomeView(
            this::newDocument,
            this::open,
            () -> SettingsView.show(stage, settings, policy.defaults()),
            document == null ? null : this::showCanvas,
            recentEntries,
            this::openPath,
            this::clearRecent));
  }

  private void showCanvas() {
    home = false;
    setTop(toolbar);
    setLeft(toolRail);
    setBottom(statusBar);
    setCenter(canvas);
    refresh();
    viewport.requestFocus();
  }

  private void updateRecent(List<RecentProjectsStore.Entry> entries) {
    if (!settings.current().appearance().rememberRecent()) return;
    recentEntries = entries;
    if (home && !busy) showHome();
  }

  private void remember(Path file) {
    if (!settings.current().appearance().rememberRecent()) return;
    jobs.submit(() -> recents.remember(file))
        .whenComplete(
            (entries, error) -> {
              if (error == null) Platform.runLater(() -> updateRecent(entries));
            });
  }

  private void clearRecent() {
    jobs.submit(
            () -> {
              recents.clear();
              return null;
            })
        .whenComplete(
            (unused, error) ->
                Platform.runLater(
                    () -> {
                      if (error != null) failure(error);
                      else {
                        recentEntries = List.of();
                        if (home && !busy) showHome();
                      }
                    }));
  }

  private void configure(UserSettings value) {
    ThemeManager.setTheme(value.appearance().theme());
    viewport.configure(value);
    if (rememberingRecent && !value.appearance().rememberRecent()) {
      recentEntries = List.of();
      if (home && !busy) showHome();
      clearRecent();
    }
    rememberingRecent = value.appearance().rememberRecent();
    if (history != null)
      history.configure(value.performance().undoMiB(), value.performance().undoCommands());
    if (autosave != null) {
      autosave.stop();
      autosave = null;
    }
    if (value.performance().autosaveSeconds() > 0) {
      autosave =
          new Timeline(
              new KeyFrame(
                  Duration.seconds(value.performance().autosaveSeconds()), e -> autosave()));
      autosave.setCycleCount(Timeline.INDEFINITE);
      autosave.play();
    }
    refresh();
  }

  private void install(DrawingDocument next, Path file) {
    document = next;
    path = file;
    var p = settings.current().performance();
    history = new CommandManager(next, p.undoMiB(), p.undoCommands());
    viewport.setDocument(next, history);
    selectLayer(0);
    recoveredRevision = -1;
    Color initialInk = selectedPreset == InkPreset.PEN ? defaultInk(next) : color.getValue();
    color.setValue(initialInk);
    viewport.setInk(initialInk);
    showCanvas();
  }

  private Color defaultInk(DrawingDocument drawing) {
    return drawing != null && Color.web(drawing.canvas().background()).getBrightness() >= .65
        ? Color.web("#222222")
        : Color.web(workspace.defaultInk());
  }

  private long lastPenReading;

  public boolean isPenSurface(double x, double y) {
    return !home
        && !busy
        && !viewport.isDisabled()
        && !(focusControls.isVisible() && focusControls.contains(focusControls.sceneToLocal(x, y)))
        && viewport.contains(viewport.sceneToLocal(x, y));
  }

  /** Receives logical scene coordinates from the tablet driver. */
  public boolean penSample(InputSample sample, boolean first, CanvasViewport.PenAction action) {
    double x = sample.x(), y = sample.y();
    if (first && !isPenSurface(x, y)) return false;
    var local = viewport.sceneToLocal(x, y);
    InputSample translated = sample.at(local.getX(), local.getY());
    if (first) return viewport.beginPen(translated, action);
    viewport.movePen(translated);
    return true;
  }

  public void tabletReading(double pressure, double tiltX, double tiltY) {
    long now = System.nanoTime();
    if (now - lastPenReading < 100_000_000L) return;
    lastPenReading = now;
    String force = Double.isFinite(pressure) ? Math.round(pressure * 100) + "%" : "unavailable";
    String tilt =
        !Double.isFinite(tiltX) && !Double.isFinite(tiltY)
            ? "unavailable"
            : formatTilt(tiltX) + " / " + formatTilt(tiltY);
    message("Pen pressure " + force + "   |   Tilt " + tilt);
  }

  private static String formatTilt(double degrees) {
    return Double.isFinite(degrees) ? Math.round(degrees) + "\u00b0" : "\u2014";
  }

  private void refresh() {
    if (home || document == null) return;
    var bounds = document.canvas().bounds();
    stage.setTitle(
        (document.dirty() ? "• " : "")
            + (path == null ? "Untitled" : path.getFileName())
            + " — Paint Studio");
    status.setText(
        String.format(
            "%.0f × %.0f   |   %.0f%%", bounds.width(), bounds.height(), viewport.zoom() * 100));
    undo.setDisable(!history.canUndo());
    redo.setDisable(!history.canRedo());
    if (activeLayer >= document.layerCount()) selectLayer(document.layerCount() - 1);
  }

  private static MenuItem item(String title, Runnable action) {
    MenuItem item = new MenuItem(title);
    item.setOnAction(event -> action.run());
    return item;
  }

  private Menu layerMenu() {
    Menu active = new Menu("Active layer");
    MenuItem visibility = item("Hide active layer", () -> editLayers("visibility"));
    Menu menu =
        new Menu(
            "Layer",
            null,
            active,
            new SeparatorMenuItem(),
            item("Add layer", () -> editLayers("add")),
            item("Raise layer", () -> editLayers("raise")),
            item("Lower layer", () -> editLayers("lower")),
            visibility);
    menu.setOnShowing(
        event -> {
          active.getItems().clear();
          ToggleGroup choices = new ToggleGroup();
          for (int i = document.layerCount() - 1; i >= 0; i--) {
            int index = i;
            String name = document.layerName(i) + (document.layerVisible(i) ? "" : " (hidden)");
            RadioMenuItem choice = new RadioMenuItem(name);
            choice.setToggleGroup(choices);
            choice.setSelected(i == activeLayer);
            choice.setOnAction(e -> selectLayer(index));
            active.getItems().add(choice);
          }
          visibility.setText(
              document.layerVisible(activeLayer) ? "Hide active layer" : "Show active layer");
        });
    return menu;
  }

  private void selectLayer(int index) {
    activeLayer = index;
    viewport.setActiveLayer(index);
  }

  private void editLayers(String action) {
    viewport.cancel();
    var before = document.layers();
    var after = new ArrayList<>(before);
    int index = activeLayer, target = index;
    switch (action) {
      case "add" -> {
        after.add(
            new Layer(
                UUID.randomUUID().toString(), "Layer " + (after.size() + 1), true, List.of()));
        target = after.size() - 1;
      }
      case "raise" -> {
        if (index == after.size() - 1) return;
        Collections.swap(after, index, index + 1);
        target++;
      }
      case "lower" -> {
        if (index == 0) return;
        Collections.swap(after, index, index - 1);
        target--;
      }
      case "visibility" -> {
        var l = after.get(index);
        after.set(index, new Layer(l.id(), l.name(), !l.visible(), l.objects()));
      }
      default -> throw new IllegalArgumentException("Unknown layer action");
    }
    try {
      history.execute(new LayerEdit("layer." + action, before, after));
      selectLayer(target);
      refresh();
      viewport.invalidate();
    } catch (IllegalArgumentException error) {
      failure(error);
    }
  }

  private void message(String text) {
    notice.setText(text);
  }

  private void failure(Throwable error) {
    while (error.getCause() != null) error = error.getCause();
    AppLogger.getInstance()
        .error(LogCategory.DOCUMENT, EditorView.class, "editor.operation.failed", error);
    message(error.getMessage() == null ? "Operation failed" : error.getMessage());
    Alert alert = new Alert(Alert.AlertType.ERROR, messageText(), ButtonType.OK);
    alert.setTitle("Paint Studio");
    alert.setHeaderText("The operation could not be completed");
    themed(alert);
    alert.showAndWait();
  }

  private String messageText() {
    return notice.getText();
  }

  private void themed(Dialog<?> dialog) {
    ThemedDialogs.install(dialog, stage);
  }

  private boolean available() {
    if (busy) {
      message("Please wait for the current file operation to finish.");
      return false;
    }
    return true;
  }

  private void guard(Runnable next) {
    if (!available()) return;
    viewport.cancel();
    if (document == null || !document.dirty()) {
      next.run();
      return;
    }
    ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
    ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
    Alert prompt =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "Save your changes before continuing?",
            save,
            discard,
            ButtonType.CANCEL);
    prompt.setTitle("Unsaved drawing");
    prompt.setHeaderText("Keep your changes?");
    themed(prompt);
    var answer = prompt.showAndWait().orElse(ButtonType.CANCEL);
    if (answer == save)
      save(
          false,
          () -> {
            if (!document.dirty()) next.run();
            else message("New edits were made while saving. Save again before continuing.");
          });
    else if (answer == discard) next.run();
  }

  public void requestClose() {
    if (closing) return;
    guard(
        () -> {
          if (document == null) {
            Platform.exit();
            return;
          }
          closing = true;
          setDisable(true);
          String documentId = document.id();
          jobs.submit(
                  () -> {
                    recovery.clearDocument(documentId);
                    return null;
                  })
              .whenComplete(
                  (v, error) ->
                      Platform.runLater(
                          () -> {
                            if (error != null) {
                              closing = false;
                              setDisable(false);
                              failure(error);
                            } else Platform.exit();
                          }));
        });
  }

  private void newDocument() {
    if (!available()) return;
    NewDocumentDialog.show(stage, settings.current())
        .ifPresent(
            options ->
                guard(
                    () -> {
                      var current = settings.current();
                      var next =
                          new DrawingDocument(
                              new UserSettings(
                                  1, current.appearance(), options, current.performance()),
                              policy);
                      if (document == null) {
                        install(next, null);
                        return;
                      }
                      busy = true;
                      setDisable(true);
                      String previousId = document.id();
                      jobs.submit(
                              () -> {
                                recovery.clearDocument(previousId);
                                return null;
                              })
                          .whenComplete(
                              (unused, error) ->
                                  Platform.runLater(
                                      () -> {
                                        busy = false;
                                        setDisable(false);
                                        if (error == null) install(next, null);
                                        else failure(error);
                                      }));
                    }));
  }

  private FileChooser chooser(String title, String extension) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    chooser
        .getExtensionFilters()
        .add(
            new FileChooser.ExtensionFilter(
                extension.equals("png") ? "PNG image" : "Paint Studio project", "*." + extension));
    return chooser;
  }

  private void open() {
    if (!available()) return;
    var file = chooser("Open drawing", "pspaint").showOpenDialog(stage);
    if (file != null) openPath(file.toPath());
  }

  private void openPath(Path file) {
    guard(
        () -> {
          busy = true;
          setDisable(true);
          String previousId = document == null ? null : document.id();
          service
              .open(file)
              .thenCompose(
                  snapshot -> {
                    if (previousId == null)
                      return java.util.concurrent.CompletableFuture.completedFuture(snapshot);
                    return jobs.submit(
                        () -> {
                          recovery.clearDocument(previousId);
                          return snapshot;
                        });
                  })
              .whenComplete(
                  (snapshot, error) ->
                      Platform.runLater(
                          () -> {
                            busy = false;
                            setDisable(false);
                            if (error != null) failure(error);
                            else {
                              install(new DrawingDocument(snapshot, policy), file);
                              remember(file);
                              message("Drawing opened");
                            }
                          }));
        });
  }

  private void save(boolean choose, Runnable completed) {
    if (!available()) return;
    Path destination = path;
    if (choose || destination == null) {
      var chooser = chooser("Save editable drawing", "pspaint");
      chooser.setInitialFileName("drawing.pspaint");
      var file = chooser.showSaveDialog(stage);
      if (file == null) return;
      destination = file.toPath();
    }
    Path target = destination;
    DrawingDocument saving = document;
    DocumentSnapshot snapshot = saving.snapshot();
    busy = true;
    message("Saving…");
    service
        .save(target, snapshot)
        .whenComplete(
            (revision, error) ->
                Platform.runLater(
                    () -> {
                      busy = false;
                      if (error != null) failure(error);
                      else if (document == saving) {
                        saving.markSaved(revision);
                        path = target;
                        remember(target);
                        refresh();
                        message("Drawing saved");
                        if (!saving.dirty()) {
                          recoveredRevision = -1;
                          jobs.submit(
                              () -> {
                                recovery.clearDocument(snapshot.id());
                                return null;
                              });
                        }
                        completed.run();
                      }
                    }));
  }

  private void export() {
    if (!available()) return;
    viewport.cancel();
    ChoiceDialog<String> choice =
        new ChoiceDialog<>("Artwork bounds", "Artwork bounds", "Full canvas");
    choice.setTitle("Export PNG");
    choice.setHeaderText("Choose the export area");
    themed(choice);
    var selected = choice.showAndWait();
    if (selected.isEmpty()) return;
    var bounds =
        selected.get().equals("Full canvas")
            ? document.canvas().bounds()
            : document.contentBounds().orElse(document.canvas().bounds());
    long pixels = (long) Math.ceil(bounds.width()) * (long) Math.ceil(bounds.height());
    if (pixels > policy.maxExportPixels()) {
      failure(
          new IllegalArgumentException(
              "Export is too large (" + pixels + " pixels). Use smaller artwork bounds."));
      return;
    }
    var chooser = chooser("Export PNG", "png");
    chooser.setInitialFileName("drawing.png");
    var file = chooser.showSaveDialog(stage);
    if (file == null) return;
    var snapshot = document.snapshot();
    busy = true;
    message("Exporting…");
    jobs.submit(
            () -> {
              new PngExporter().export(file.toPath(), snapshot, bounds, policy.maxExportPixels());
              return null;
            })
        .whenComplete(
            (v, error) ->
                Platform.runLater(
                    () -> {
                      busy = false;
                      if (error != null) failure(error);
                      else {
                        AppLogger.getInstance()
                            .info(
                                LogCategory.DOCUMENT,
                                EditorView.class,
                                "document.exported",
                                Map.of("documentId", snapshot.id(), "pixels", pixels));
                        message("PNG exported");
                      }
                    }));
  }

  private void autosave() {
    if (document == null
        || closing
        || busy
        || recovering
        || !document.dirty()
        || document.revision() == recoveredRevision) return;
    var snapshot = document.snapshot();
    var preferences = settings.current().performance();
    recovering = true;
    jobs.submit(
            () -> {
              recovery.save(snapshot, preferences);
              return null;
            })
        .whenComplete(
            (v, error) ->
                Platform.runLater(
                    () -> {
                      recovering = false;
                      if (error == null) {
                        if (document.id().equals(snapshot.id()))
                          recoveredRevision = snapshot.revision();
                        AppLogger.getInstance()
                            .info(
                                LogCategory.DOCUMENT,
                                EditorView.class,
                                "recovery.saved",
                                Map.of(
                                    "documentId", snapshot.id(), "revision", snapshot.revision()));
                      } else message("Recovery save failed. Save your drawing manually.");
                    }));
  }

  public void offerRecovery() {
    busy = true;
    setDisable(true);
    jobs.submit(recovery::candidates)
        .whenComplete(
            (paths, error) ->
                Platform.runLater(
                    () -> {
                      if (error != null) {
                        busy = false;
                        setDisable(false);
                        message("Recovery files could not be checked.");
                        return;
                      }
                      if (paths.isEmpty()) {
                        busy = false;
                        setDisable(false);
                        return;
                      }
                      Alert prompt =
                          new Alert(
                              Alert.AlertType.CONFIRMATION,
                              "An earlier recovery drawing is available. Restore the most recent"
                                  + " version?",
                              ButtonType.YES,
                              ButtonType.NO);
                      prompt.setTitle("Recover drawing");
                      prompt.setHeaderText("Restore previous work?");
                      themed(prompt);
                      if (prompt.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
                        busy = false;
                        setDisable(false);
                        return;
                      }
                      service
                          .open(paths.getFirst())
                          .whenComplete(
                              (snapshot, failed) ->
                                  Platform.runLater(
                                      () -> {
                                        busy = false;
                                        setDisable(false);
                                        if (failed != null) failure(failed);
                                        else {
                                          install(new DrawingDocument(snapshot, policy), null);
                                          document.markRecovered();
                                          refresh();
                                          message(
                                              "Recovered drawing — save a project file to keep"
                                                  + " it.");
                                        }
                                      }));
                    }));
  }

  @Override
  public void close() {
    closing = true;
    if (autosave != null) autosave.stop();
    viewport.close();
  }
}
