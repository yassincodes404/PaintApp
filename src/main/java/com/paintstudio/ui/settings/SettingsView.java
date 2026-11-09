package com.paintstudio.ui.settings;

import com.paintstudio.settings.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

public final class SettingsView {
  private SettingsView() {}

  public static void show(Window owner, SettingsManager manager, UserSettings defaults) {
    Dialog<Void> dialog = new Dialog<>();
    dialog.setTitle("Paint Studio settings");
    com.paintstudio.ui.dialog.ThemedDialogs.install(dialog, owner);
    var pane = dialog.getDialogPane();
    pane.setPrefWidth(620);
    pane.getStyleClass().add("preferences-dialog");
    ComboBox<String> theme = new ComboBox<>();
    theme.getItems().setAll("dark", "light");
    CheckBox splash = new CheckBox("Show splash screen"),
        grid = new CheckBox("Show workspace grid"),
        expand = new CheckBox("Expand canvas automatically");
    CheckBox welcome = new CheckBox("Show welcome screen at startup"),
        fullScreen = new CheckBox("Use full screen in canvas focus mode"),
        remember = new CheckBox("Remember recent projects");
    TextField width = new TextField(),
        height = new TextField(),
        background = new TextField(),
        step = new TextField(),
        margin = new TextField();
    TextField history = new TextField(),
        commands = new TextField(),
        autosave = new TextField(),
        generations = new TextField(),
        recovery = new TextField();
    Label error = new Label();
    error.setWrapText(true);
    error.getStyleClass().add("settings-error");
    var pages =
        java.util.List.of(
            tab(
                "Appearance",
                form(
                    "Theme",
                    theme,
                    "",
                    splash,
                    "",
                    grid,
                    "",
                    welcome,
                    "",
                    fullScreen,
                    "",
                    remember)),
            tab(
                "Canvas",
                form(
                    "New document width",
                    width,
                    "New document height",
                    height,
                    "Background (#RRGGBB / #RRGGBBAA)",
                    background,
                    "",
                    expand,
                    "Expansion step",
                    step,
                    "Expansion margin",
                    margin)),
            tab(
                "Resources",
                form(
                    "Undo memory (MiB)",
                    history,
                    "Undo command count",
                    commands,
                    "Autosave seconds (0 disables)",
                    autosave,
                    "Recovery generations",
                    generations,
                    "Recovery disk limit (MiB)",
                    recovery)));
    java.util.function.Consumer<UserSettings> populate =
        s -> {
          theme.setValue(s.appearance().theme());
          splash.setSelected(s.appearance().showSplash());
          grid.setSelected(s.appearance().showGrid());
          welcome.setSelected(s.appearance().showWelcome());
          fullScreen.setSelected(s.appearance().fullScreenFocus());
          remember.setSelected(s.appearance().rememberRecent());
          width.setText("" + s.canvas().width());
          height.setText("" + s.canvas().height());
          background.setText(s.canvas().background());
          expand.setSelected(s.canvas().autoExpand());
          step.setText("" + s.canvas().expansionStep());
          margin.setText("" + s.canvas().expansionMargin());
          history.setText("" + s.performance().undoMiB());
          commands.setText("" + s.performance().undoCommands());
          autosave.setText("" + s.performance().autosaveSeconds());
          generations.setText("" + s.performance().recoveryGenerations());
          recovery.setText("" + s.performance().recoveryMiB());
        };
    populate.accept(manager.current());
    Label note =
        new Label(
            "Canvas size and background apply to new documents. Splash changes apply next launch. "
                + "Reducing undo limits can remove the oldest history.");
    note.setWrapText(true);
    note.getStyleClass().add("muted");
    ListView<String> navigation = new ListView<>();
    navigation.setId("settings-sections");
    navigation.getStyleClass().add("preferences-nav");
    navigation.getItems().setAll(pages.stream().map(Tab::getText).toList());
    navigation.setAccessibleText("Settings sections");
    navigation.setPrefWidth(133);
    navigation.setMaxWidth(133);
    navigation.setMinWidth(133);
    navigation.setPrefHeight(270);
    Label section = new Label();
    section.getStyleClass().add("preferences-section");
    StackPane form = new StackPane();
    VBox detail = new VBox(9, section, form, note, error);
    detail.getStyleClass().add("preferences-detail");
    HBox.setHgrow(detail, Priority.ALWAYS);
    navigation
        .getSelectionModel()
        .selectedIndexProperty()
        .addListener(
            (observable, old, selected) -> {
              if (selected.intValue() < 0) return;
              Tab page = pages.get(selected.intValue());
              section.setText(page.getText());
              form.getChildren().setAll(page.getContent());
            });
    navigation.getSelectionModel().selectFirst();
    pane.setContent(new HBox(navigation, detail));
    ButtonType restore = new ButtonType("Restore defaults", ButtonBar.ButtonData.LEFT);
    pane.getButtonTypes().setAll(restore, ButtonType.APPLY, ButtonType.CANCEL);
    pane.lookupButton(restore)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              e.consume();
              populate.accept(defaults);
            });
    pane.lookupButton(ButtonType.APPLY)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              e.consume();
              try {
                var previous = manager.current();
                UserSettings next =
                    new UserSettings(
                        1,
                        new UserSettings.Appearance(
                            theme.getValue(),
                            splash.isSelected(),
                            grid.isSelected(),
                            welcome.isSelected(),
                            fullScreen.isSelected(),
                            remember.isSelected()),
                        new UserSettings.Canvas(
                            Integer.parseInt(width.getText()),
                            Integer.parseInt(height.getText()),
                            background.getText(),
                            expand.isSelected(),
                            Integer.parseInt(step.getText()),
                            Integer.parseInt(margin.getText()),
                            previous.canvas().minZoom(),
                            previous.canvas().maxZoom()),
                        new UserSettings.Performance(
                            Integer.parseInt(history.getText()),
                            Integer.parseInt(commands.getText()),
                            Integer.parseInt(autosave.getText()),
                            Integer.parseInt(generations.getText()),
                            Integer.parseInt(recovery.getText())));
                SettingsValidator.validate(next);
                pane.setDisable(true);
                manager
                    .apply(next)
                    .whenComplete(
                        (unused, failure) ->
                            Platform.runLater(
                                () -> {
                                  pane.setDisable(false);
                                  if (failure == null) dialog.close();
                                  else
                                    error.setText(
                                        "Settings could not be saved: " + failure.getMessage());
                                }));
              } catch (IllegalArgumentException failure) {
                error.setText(failure.getMessage());
              }
            });
    dialog.showAndWait();
  }

  private static Tab tab(String title, GridPane content) {
    Tab tab = new Tab(title, content);
    tab.setClosable(false);
    return tab;
  }

  private static GridPane form(Object... entries) {
    GridPane grid = new GridPane();
    grid.setHgap(16);
    grid.setVgap(8);
    grid.setPadding(new Insets(4, 0, 8, 0));
    for (int i = 0; i < entries.length; i += 2) {
      grid.add(new Label((String) entries[i]), 0, i / 2);
      grid.add((javafx.scene.Node) entries[i + 1], 1, i / 2);
    }
    return grid;
  }
}
