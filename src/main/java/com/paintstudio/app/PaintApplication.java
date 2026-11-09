package com.paintstudio.app;

import com.paintstudio.app.config.AppConfig;
import com.paintstudio.app.config.ConfigLoader;
import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.app.config.LoggingConfigLoader;
import com.paintstudio.app.config.WorkspaceConfig;
import com.paintstudio.common.concurrent.BackgroundJobs;
import com.paintstudio.common.logging.*;
import com.paintstudio.editor.EditorView;
import com.paintstudio.settings.*;
import com.paintstudio.ui.dialog.StartupMessage;
import com.paintstudio.ui.splash.SplashScreen;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class PaintApplication extends Application {
  private final AppLogger logger = AppLogger.getInstance();
  private final long started = System.nanoTime();
  private AppConfig config;
  private LoggingConfigLoader.LoadResult logging;
  private Task<List<Image>> preparation;
  private UiEventLogger inputLogging;
  private Thread.UncaughtExceptionHandler previousExceptionHandler;
  private InstanceManager instance;
  private String startupRejection;
  private boolean duplicateInstance;
  private EditorConfig editorConfig;
  private WorkspaceConfig workspaceConfig;
  private com.paintstudio.editor.interaction.WindowsPenInput tablet;
  private BackgroundJobs jobs;
  private SettingsManager settings;
  private EditorView editor;

  @Override
  public void init() {
    try {
      instance = InstanceManager.tryAcquire();
      if (instance == null) {
        duplicateInstance = true;
        startupRejection = "Close the existing application before launching Paint Studio again.";
        return;
      }
    } catch (IOException | RuntimeException error) {
      startupRejection =
          "Paint Studio could not verify exclusive application access. "
              + "Startup has been cancelled. Check access to the application state directory.";
      System.err.println("Paint Studio instance check failed: " + error);
      return;
    }
    // Retain ownership through JVM shutdown, including failed initialization.
    try {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      logger.close();
                    } finally {
                      try {
                        instance.close();
                      } catch (IOException error) {
                        System.err.println("Cannot close application lock: " + error);
                      }
                    }
                  },
                  "paint-studio-shutdown"));
    } catch (RuntimeException error) {
      try {
        instance.close();
      } catch (IOException closingError) {
        error.addSuppressed(closingError);
      }
      throw error;
    }
    logging = LoggingConfigLoader.load();
    logger.configure(logging.config());
    logger.start();
    previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          logger.error(LogCategory.SYSTEM, PaintApplication.class, "uncaught.exception", error);
          if (previousExceptionHandler != null)
            previousExceptionHandler.uncaughtException(thread, error);
          else error.printStackTrace(System.err);
        });
    config = ConfigLoader.load();
    editorConfig = EditorConfig.load();
    workspaceConfig = WorkspaceConfig.load();
    jobs = new BackgroundJobs(editorConfig.ioQueueCapacity(), editorConfig.shutdownSeconds());
    var store = new SettingsStore(AppPaths.config());
    var loaded = store.load(editorConfig.defaults());
    settings = new SettingsManager(store, loaded, jobs, Platform::runLater);
    ThemeManager.initialize(loaded.appearance().theme());
    if (logging.config().flag("startup.configuration"))
      logger.info(
          LogCategory.CONFIGURATION,
          PaintApplication.class,
          "editor.configuration.effective",
          Map.of(
              "settings",
              loaded.toString(),
              "policy",
              editorConfig.toString(),
              "workspace",
              workspaceConfig.toString(),
              "source",
              "editor.properties + workspace.properties + settings.json overrides"));
    logger.info(
        LogCategory.STARTUP,
        PaintApplication.class,
        "application.starting",
        Map.of(
            "name",
            config.name(),
            "version",
            config.version(),
            "pid",
            ProcessHandle.current().pid()));
  }

  private void logStartupConfiguration() {
    for (String diagnostic : logging.diagnostics()) {
      logger.warn(
          LogCategory.CONFIGURATION,
          PaintApplication.class,
          "logging.configuration.warning",
          Map.of("message", diagnostic));
    }
    if (logging.config().flag("startup.environment")) {
      logger.info(
          LogCategory.STARTUP,
          PaintApplication.class,
          "startup.environment",
          Map.of(
              "javaVersion",
              System.getProperty("java.version"),
              "javaVendor",
              System.getProperty("java.vendor"),
              "javafxVersion",
              System.getProperty("javafx.version", "unknown"),
              "os",
              System.getProperty("os.name"),
              "osVersion",
              System.getProperty("os.version"),
              "architecture",
              System.getProperty("os.arch"),
              "heapMaxBytes",
              Runtime.getRuntime().maxMemory()));
    }
    if (logging.config().flag("startup.configuration")) {
      logging
          .config()
          .values()
          .forEach(
              (key, value) ->
                  logger.info(
                      LogCategory.CONFIGURATION,
                      PaintApplication.class,
                      "configuration.effective",
                      Map.of("key", key, "value", value, "origin", logging.origins().get(key))));
      Map<String, Object> effective = new LinkedHashMap<>();
      effective.put("app.name", config.name());
      effective.put("app.version", config.version());
      effective.put("app.description", config.description());
      effective.put("window.width", config.windowWidth());
      effective.put("window.height", config.windowHeight());
      effective.put("theme.default", config.defaultTheme());
      effective.put("icons.directory", config.iconsDirectory());
      effective.put("icons.sizes", config.iconSizes().toString());
      effective.put("splash.enabled", config.splash().enabled());
      effective.put("splash.animation", config.splash().animation());
      effective.put("splash.duration.ms", config.splash().duration().toMillis());
      effective.put("splash.width", config.splash().width());
      effective.put("splash.height", config.splash().height());
      effective.put("splash.show-name", config.splash().showName());
      effective.put("splash.show-version", config.splash().showVersion());
      logger.info(
          LogCategory.CONFIGURATION,
          PaintApplication.class,
          "application.configuration.effective",
          effective);
    }
  }

  @Override
  public void start(Stage stage) {
    if (startupRejection != null) {
      StartupMessage.show(duplicateInstance, startupRejection);
      Platform.exit();
      return;
    }
    long preparationStarted = System.nanoTime();
    CompletableFuture<Void> editorPrepared = new CompletableFuture<>();
    preparation =
        new Task<>() {
          @Override
          protected List<Image> call() {
            logger.info(
                LogCategory.STARTUP,
                PaintApplication.class,
                "startup.preparation.started",
                Map.of());
            logStartupConfiguration();
            return AppIconManager.load(config);
          }
        };
    preparation.setOnSucceeded(
        event -> {
          try {
            prepareEditor(stage, preparation.getValue());
            logger.info(
                LogCategory.STARTUP,
                PaintApplication.class,
                "startup.preparation.completed",
                Map.of("elapsedMs", (System.nanoTime() - preparationStarted) / 1_000_000));
            editorPrepared.complete(null);
          } catch (RuntimeException error) {
            editorPrepared.completeExceptionally(error);
          }
        });
    preparation.setOnFailed(
        event -> editorPrepared.completeExceptionally(preparation.getException()));

    Platform.setImplicitExit(false);
    var splash = config.splash();
    SplashScreen.show(
        new com.paintstudio.ui.splash.SplashConfig(
            splash.enabled() && settings.current().appearance().showSplash(),
            splash.animation(),
            splash.duration(),
            splash.width(),
            splash.height(),
            splash.showName(),
            splash.showVersion()),
        editorPrepared,
        () -> {
          try {
            editorPrepared.join();
            stage.show();
            tablet = com.paintstudio.editor.interaction.WindowsPenInput.attach(stage, editor);
            editor.offerRecovery();
            logger.info(
                LogCategory.STARTUP,
                PaintApplication.class,
                "application.ready",
                Map.of("elapsedMs", (System.nanoTime() - started) / 1_000_000));
            Platform.setImplicitExit(true);
          } catch (RuntimeException error) {
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            logger.error(
                LogCategory.STARTUP, PaintApplication.class, "startup.preparation.failed", cause);
            StartupMessage.show(
                false, "Paint Studio could not prepare the editor. Check the application log.");
            Platform.exit();
          }
        });

    Thread startupWorker = new Thread(preparation, "paint-studio-startup");
    startupWorker.setDaemon(true);
    startupWorker.start();
  }

  private void prepareEditor(Stage stage, List<Image> icons) {
    stage.getIcons().setAll(icons);
    stage.initStyle(StageStyle.UNDECORATED);
    stage.setMinWidth(640);
    stage.setMinHeight(420);
    editor = new EditorView(stage, settings, editorConfig, workspaceConfig, jobs);
    ApplicationShell shell = new ApplicationShell(stage, editor, icons);
    shell.bindChromeHidden(editor.focusModeProperty());
    Scene scene = new Scene(shell, config.windowWidth(), config.windowHeight());
    ThemeManager.apply(scene);
    logger.info(
        LogCategory.CONFIGURATION,
        PaintApplication.class,
        "theme.selected",
        Map.of("theme", ThemeManager.currentTheme()));
    inputLogging = UiEventLogger.attach(scene);
    stage.setScene(scene);
    stage.setOnCloseRequest(
        event -> {
          event.consume();
          editor.requestClose();
        });
  }

  @Override
  public void stop() {
    if (startupRejection != null) return;
    if (preparation != null) preparation.cancel();
    if (inputLogging != null) inputLogging.close();
    if (tablet != null) tablet.close();
    if (editor != null) editor.close();
    if (jobs != null) jobs.close();
    logger.info(LogCategory.SYSTEM, PaintApplication.class, "application.stopping", Map.of());
    Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler);
    logger.close();
    // Instance ownership is released by the JVM shutdown hook.
  }

  public static void main(String[] args) {
    launch(args);
  }
}
