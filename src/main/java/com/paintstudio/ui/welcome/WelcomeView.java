package com.paintstudio.ui.welcome;

import com.paintstudio.settings.RecentProjectsStore.Entry;
import com.paintstudio.ui.component.WorkspaceIcon;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class WelcomeView extends BorderPane {
  public WelcomeView(
      Runnable create,
      Runnable open,
      Runnable settings,
      Runnable resume,
      List<Entry> recent,
      Consumer<Path> openRecent,
      Runnable clear) {
    setId("welcome-view");
    getStyleClass().add("welcome-view");
    Label eyebrow = new Label("PAINT STUDIO");
    eyebrow.getStyleClass().add("welcome-eyebrow");
    Label title = new Label("A space for your ideas.");
    title.getStyleClass().add("welcome-title");
    Label subtitle = new Label("Write, sketch, and create on an open canvas.");
    subtitle.getStyleClass().add("muted");
    HBox brand =
        new HBox(
            16,
            new WorkspaceIcon(WorkspaceIcon.Kind.PALETTE, 44),
            new VBox(4, eyebrow, title, subtitle));
    brand.setAlignment(Pos.CENTER_LEFT);
    Button fresh =
        card("New document", "Start with a fresh canvas", WorkspaceIcon.Kind.NEW, create);
    fresh.setId("welcome-new");
    Button browse =
        card("Open project", "Continue from a saved drawing", WorkspaceIcon.Kind.OPEN, open);
    browse.setId("welcome-open");
    HBox actions = new HBox(16, fresh, browse);
    HBox.setHgrow(fresh, Priority.ALWAYS);
    HBox.setHgrow(browse, Priority.ALWAYS);
    VBox content = new VBox(26, brand, actions);
    content.setMaxWidth(820);
    if (resume != null) {
      Button back =
          card(
              "Continue current drawing",
              "Your open work is still here",
              WorkspaceIcon.Kind.BRUSH,
              resume);
      content.getChildren().add(back);
    }
    Label heading = new Label("Recent projects");
    heading.getStyleClass().add("section-title");
    Button clearButton = new Button("Clear list");
    clearButton.setOnAction(e -> clear.run());
    clearButton.setDisable(recent.isEmpty());
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    content.getChildren().add(new HBox(12, heading, spacer, clearButton));
    VBox rows = new VBox(8);
    if (recent.isEmpty()) {
      Label empty = new Label("Your saved projects will appear here.");
      empty.getStyleClass().add("muted");
      rows.getChildren().add(empty);
    }
    for (Entry entry : recent) {
      Path path = Path.of(entry.path());
      Button button =
          card(
              path.getFileName().toString(),
              path.getParent().toString(),
              WorkspaceIcon.Kind.OPEN,
              () -> openRecent.accept(path));
      button.getStyleClass().add("recent-project");
      rows.getChildren().add(button);
    }
    content.getChildren().add(rows);
    Button preferences =
        card(
            "Settings",
            "Appearance, canvas, and resource preferences",
            WorkspaceIcon.Kind.SETTINGS,
            settings);
    preferences.setId("welcome-settings");
    content.getChildren().add(preferences);
    StackPane container = new StackPane(content);
    container.setAlignment(Pos.TOP_CENTER);
    container.getStyleClass().add("welcome-container");
    ScrollPane scroll = new ScrollPane(container);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.getStyleClass().add("welcome-scroll");
    setCenter(scroll);
  }

  private static Button card(String title, String note, WorkspaceIcon.Kind kind, Runnable action) {
    Label label = new Label(title);
    label.getStyleClass().add("card-title");
    Label detail = new Label(note);
    detail.getStyleClass().add("muted");
    detail.setMaxWidth(Double.MAX_VALUE);
    VBox text = new VBox(5, label, detail);
    text.setMinWidth(0);
    HBox.setHgrow(text, Priority.ALWAYS);
    HBox content = new HBox(16, new WorkspaceIcon(kind, 28), text);
    content.setAlignment(Pos.CENTER_LEFT);
    Button button = new Button();
    button.setGraphic(content);
    button.setMaxWidth(Double.MAX_VALUE);
    button.setAccessibleText(title);
    button.setTooltip(new Tooltip(title + "\n" + note));
    button.getStyleClass().add("welcome-card");
    button.setOnAction(e -> action.run());
    return button;
  }
}
