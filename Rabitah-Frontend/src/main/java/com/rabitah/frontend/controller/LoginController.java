package com.rabitah.frontend.controller;

import com.rabitah.frontend.AppContext;
import com.rabitah.frontend.model.AuthResponse;
import java.io.ByteArrayInputStream;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

public final class LoginController {
    private final AppContext context;

    @FXML private TextField loginId;
    @FXML private PasswordField password;
    @FXML private TextField visiblePassword;
    @FXML private CheckBox showPassword;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;
    @FXML private ImageView loginVisual;
    @FXML private StackPane loginVisualStage;
    private Timeline approvalPolling;
    private String waitingId;
    private String waitingPassword;
    private boolean requestRunning;
    private AutoCloseable realtimeSubscription;

    public LoginController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        visiblePassword.textProperty().bindBidirectional(password.textProperty());
        loginVisual.fitWidthProperty().bind(loginVisualStage.widthProperty());
        loginVisual.fitHeightProperty().bind(loginVisualStage.heightProperty());
        realtimeSubscription = context.approvals().subscribe(() -> Platform.runLater(() -> {
            if (waitingId != null && approvalPolling != null) attemptLogin(waitingId, waitingPassword, false);
        }));
        loadLoginVisual();
        Platform.runLater(loginId::requestFocus);
    }

    private void loadLoginVisual() {
        // A unique query string prevents a proxy or previous server response from reusing an older visual.
        context.api().getBytes("auth/login-visual?v=" + System.currentTimeMillis()).thenApplyAsync(bytes ->
                new Image(new ByteArrayInputStream(bytes), 900, 700, true, true), context.executor())
                .whenComplete((image, error) -> {
                    if (error == null && image != null && !image.isError()) {
                        Platform.runLater(() -> {
                            loginVisual.setImage(image);
                            loginVisual.setVisible(true);
                            loginVisual.setManaged(true);
                        });
                    }
                });
    }

    @FXML
    private void togglePasswordVisibility() {
        boolean show = showPassword.isSelected();
        visiblePassword.setVisible(show);
        visiblePassword.setManaged(show);
        password.setVisible(!show);
        password.setManaged(!show);
        TextField activeField = show ? visiblePassword : password;
        activeField.requestFocus();
        activeField.positionCaret(activeField.getText().length());
    }

    @FXML
    private void login() {
        stopPolling();
        errorLabel.setText("");
        String id = loginId.getText().trim();
        String enteredPassword = password.getText();
        if (id.isBlank() || enteredPassword.isBlank()) {
            errorLabel.setText("Enter both your system/student ID and password.");
            return;
        }
        attemptLogin(id, enteredPassword, true);
    }

    private void attemptLogin(String id, String enteredPassword, boolean startWaiting) {
        if (requestRunning) return;
        requestRunning = true;
        loginButton.setDisable(true);
        context.auth().login(id, enteredPassword).whenComplete((response, error) ->
                Platform.runLater(() -> {
                    requestRunning = false;
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        String message = cause.getMessage() == null ? "Sign-in failed" : cause.getMessage();
                        String lower = message.toLowerCase();
                        if (lower.contains("approval") || lower.contains("sent to the system administrator") || lower.contains("waiting for")) {
                            errorLabel.setText("Waiting for SysAdmin approval... You will enter automatically once approved.");
                            if (startWaiting || approvalPolling == null) startPolling(id, enteredPassword);
                        } else {
                            stopPolling();
                            loginButton.setDisable(false);
                            errorLabel.setText(message);
                        }
                        return;
                    }
                    stopPolling();
                    showLoginSuccess(response);
                }));
    }

    @FXML
    private void openForgotPassword() {
        Dialog<Void> dialog = new Dialog<>();
        prepareDialog(dialog);
        dialog.setTitle("Request a password reset");
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("forgot-password-dialog");
        pane.setHeaderText(null);
        pane.setPrefWidth(440);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Forgot password?");
        title.getStyleClass().add("dialog-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("dialog-close");
        close.setOnAction(event -> dismissDialog(dialog));
        header.getChildren().addAll(title, spacer, close);

        Label copy = new Label("Enter your ID and choose a new password. SysAdmin must approve the request before it takes effect.");
        copy.setWrapText(true);
        copy.getStyleClass().add("muted");
        TextField resetId = new TextField();
        resetId.setPromptText("Student or system ID");
        PasswordField newPassword = new PasswordField();
        newPassword.setPromptText("New password");
        PasswordField confirmPassword = new PasswordField();
        confirmPassword.setPromptText("Confirm new password");
        Label status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("composer-error");
        Button submit = new Button("Send reset request");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.getStyleClass().add("login-submit-button");
        submit.setOnAction(event -> {
            String id = resetId.getText().trim();
            String proposed = newPassword.getText();
            if (id.isBlank() || proposed.isBlank() || confirmPassword.getText().isBlank()) {
                status.setText("Complete every field first.");
                return;
            }
            if (!proposed.equals(confirmPassword.getText())) {
                status.setText("The new passwords do not match.");
                confirmPassword.requestFocus();
                return;
            }
            submit.setDisable(true);
            status.setText("");
            context.auth().requestPasswordReset(id, proposed).whenComplete((response, error) -> Platform.runLater(() -> {
                if (error != null) {
                    submit.setDisable(false);
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText(cause.getMessage() == null ? "Could not send reset request." : cause.getMessage());
                    return;
                }
                status.getStyleClass().remove("composer-error");
                status.getStyleClass().add("muted");
                status.setText("Request sent. Your new password will work after SysAdmin approval.");
                PauseTransition closeDelay = new PauseTransition(Duration.seconds(1.4));
                closeDelay.setOnFinished(done -> dismissDialog(dialog));
                closeDelay.play();
            }));
        });

        VBox content = new VBox(11, header, copy, new Label("Campus ID"), resetId, new Label("New password"), newPassword,
                new Label("Confirm password"), confirmPassword, status, submit);
        content.getStyleClass().add("forgot-password-content");
        pane.setContent(content);
        dialog.setOnShown(event -> resetId.requestFocus());
        dialog.show();
    }

    private void showLoginSuccess(AuthResponse response) {
        Window owner = loginButton.getScene().getWindow();
        VBox toast = new VBox(6);
        toast.setAlignment(Pos.CENTER);
        toast.getStyleClass().add("login-success-toast");
        Label icon = new Label("✓");
        icon.getStyleClass().add("login-success-icon");
        Label title = new Label("You are logged in");
        title.getStyleClass().add("login-success-title");
        Label copy = new Label("Opening your campus space…");
        copy.getStyleClass().add("login-success-copy");
        toast.getChildren().addAll(icon, title, copy);
        Popup popup = new Popup();
        popup.setAutoHide(false);
        popup.getContent().add(toast);
        popup.show(owner, owner.getX() + (owner.getWidth() - 290) / 2, owner.getY() + (owner.getHeight() - 165) / 2);
        toast.setOpacity(0);
        FadeTransition appear = new FadeTransition(Duration.millis(260), toast);
        appear.setFromValue(0);
        appear.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(720));
        FadeTransition disappear = new FadeTransition(Duration.millis(240), toast);
        disappear.setFromValue(1);
        disappear.setToValue(0);
        SequentialTransition transition = new SequentialTransition(appear, hold, disappear);
        transition.setOnFinished(event -> {
            popup.hide();
            closeRealtimeSubscription();
            context.session().open(response);
            context.router().showShell();
        });
        transition.play();
    }

    private void prepareDialog(Dialog<?> dialog) {
        if (loginButton.getScene() != null) dialog.initOwner(loginButton.getScene().getWindow());
        dialog.initStyle(StageStyle.UTILITY);
        dialog.initModality(Modality.NONE);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var nativeClose = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        if (nativeClose != null) {
            nativeClose.setVisible(false);
            nativeClose.setManaged(false);
        }
        dialog.setOnShowing(event -> {
            Scene popupScene = dialog.getDialogPane().getScene();
            if (popupScene != null) {
                String stylesheet = getClass().getResource("/com/rabitah/frontend/css/app.css").toExternalForm();
                if (!popupScene.getStylesheets().contains(stylesheet)) popupScene.getStylesheets().add(stylesheet);
            }
        });
    }

    private void dismissDialog(Dialog<?> dialog) {
        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null && scene.getWindow() != null) scene.getWindow().hide();
        else dialog.close();
    }

    private void startPolling(String id, String enteredPassword) {
        waitingId = id;
        waitingPassword = enteredPassword;
        loginId.setDisable(true);
        password.setDisable(true);
        visiblePassword.setDisable(true);
        showPassword.setDisable(true);
        loginButton.setText("Waiting for approval...");
        approvalPolling = new Timeline(new javafx.animation.KeyFrame(Duration.seconds(10), event -> attemptLogin(waitingId, waitingPassword, false)));
        approvalPolling.setCycleCount(Timeline.INDEFINITE);
        approvalPolling.play();
    }

    private void stopPolling() {
        if (approvalPolling != null) approvalPolling.stop();
        approvalPolling = null;
        waitingId = null;
        waitingPassword = null;
        loginId.setDisable(false);
        password.setDisable(false);
        visiblePassword.setDisable(false);
        showPassword.setDisable(false);
        loginButton.setDisable(false);
        loginButton.setText("Sign in");
    }

    private void closeRealtimeSubscription() {
        try { if (realtimeSubscription != null) realtimeSubscription.close(); } catch (Exception ignored) { }
        realtimeSubscription = null;
    }
}
