package com.rabitah.frontend.navigation;

import com.rabitah.frontend.AppContext;
import com.rabitah.frontend.controller.LoginController;
import com.rabitah.frontend.controller.ProfileController;
import com.rabitah.frontend.controller.ShellController;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class SceneRouter {
    private static final String DARK_MODE_PREFERENCE = "dark-mode";
    private final AppContext context;
    private final Preferences preferences = Preferences.userNodeForPackage(SceneRouter.class);
    private Stage stage;
    private Scene scene;
    private boolean darkMode;
    private final Map<Node, Animation> hoverMotions = new WeakHashMap<>();

    public SceneRouter(AppContext context) {
        this.context = context;
        darkMode = preferences.getBoolean(DARK_MODE_PREFERENCE, false);
    }

    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Rabitah — campus platform build 3.2");
        stage.getIcons().setAll(new Image(getClass().getResource(
                "/com/rabitah/frontend/images/rabitah-app-icon-round-v1.png").toExternalForm()));
        stage.setMinWidth(860);
        stage.setMinHeight(540);

        Rectangle2D desktop = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1180, desktop.getWidth() * 0.92);
        double height = Math.min(700, desktop.getHeight() * 0.88);
        Parent initialRoot = load("login.fxml", LoginController.class);
        applyTheme(initialRoot);
        scene = new Scene(initialRoot, width, height);
        scene.getStylesheets().add(getClass().getResource("/com/rabitah/frontend/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
        Platform.runLater(() -> animateScreenIn(initialRoot));
    }

    public void showLogin() { replace("login.fxml", LoginController.class); }
    public void showShell() { replace("shell.fxml", ShellController.class); }
    public void showProfile() { replace("profile.fxml", ProfileController.class); }

    public boolean isDarkMode() { return darkMode; }

    /** Theme selection is local to this desktop user and survives the next launch. */
    public void setDarkMode(boolean enabled) {
        darkMode = enabled;
        preferences.putBoolean(DARK_MODE_PREFERENCE, enabled);
        if (scene != null && scene.getRoot() != null) {
            applyTheme(scene.getRoot());
        }
    }

    /** Also used for utility dialogs, which own a separate JavaFX scene. */
    public void applyTheme(Parent root) {
        root.getStyleClass().remove("dark-mode");
        if (darkMode) {
            root.getStyleClass().add("dark-mode");
        }
    }

    private void replace(String name, Class<?> controllerType) {
        if (scene == null) throw new IllegalStateException("SceneRouter has not been started");
        Parent next = load(name, controllerType);
        applyTheme(next);
        scene.setRoot(next);
        animateScreenIn(next);
    }

    /** Shared entry motion makes sign-in, sign-out, and workspace changes feel continuous. */
    private void animateScreenIn(Parent root) {
        root.setOpacity(0);
        root.setTranslateY(12);
        FadeTransition fade = new FadeTransition(Duration.millis(280), root);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition settle = new TranslateTransition(Duration.millis(320), root);
        settle.setToY(0);
        settle.setInterpolator(Interpolator.EASE_BOTH);
        new ParallelTransition(fade, settle).play();
        installHoverMotion(root);
    }

    /** A short physical response on standard buttons—subtle enough for a productivity app. */
    private void installHoverMotion(Node node) {
        if (node instanceof Button) {
            node.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> scale(node, 1.025));
            node.addEventHandler(MouseEvent.MOUSE_EXITED, event -> scale(node, 1));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                installHoverMotion(child);
            }
        }
    }

    private void scale(Node node, double target) {
        Animation previous = hoverMotions.get(node);
        if (previous != null) {
            previous.stop();
        }
        ScaleTransition transition = new ScaleTransition(Duration.millis(125), node);
        transition.setToX(target);
        transition.setToY(target);
        transition.setInterpolator(Interpolator.EASE_OUT);
        hoverMotions.put(node, transition);
        transition.setOnFinished(event -> hoverMotions.remove(node, transition));
        transition.play();
    }

    private Parent load(String name, Class<?> controllerType) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/rabitah/frontend/fxml/" + name));
            loader.setControllerFactory(type -> {
                if (type != controllerType) throw new IllegalArgumentException("Unsupported controller: " + type);
                if (type == LoginController.class) return new LoginController(context);
                if (type == ShellController.class) return new ShellController(context);
                if (type == ProfileController.class) return new ProfileController(context);
                throw new IllegalArgumentException("Unsupported controller: " + type);
            });
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load " + name, error);
        }
    }
}
