package com.paintstudio.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

public final class PaintApplication extends Application {
    @Override
    public void start(Stage stage) {
        stage.setTitle("Paint Studio");
        stage.setScene(new Scene(new Pane(), 960, 640));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
