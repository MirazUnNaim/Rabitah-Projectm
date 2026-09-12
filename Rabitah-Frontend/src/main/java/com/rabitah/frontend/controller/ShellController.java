package com.rabitah.frontend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabitah.frontend.AppContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

/** The main desktop workspace. Chat polling is a reliable fallback when sockets reconnect. */
public final class ShellController {
    private final AppContext context;
    private final Map<String, String> paperIds = new LinkedHashMap<>();
    private final Map<String, String> paperFiles = new LinkedHashMap<>();
    private final Map<String, String> users = new LinkedHashMap<>();
    private final Map<String, String> conversations = new LinkedHashMap<>();
    private final Map<String, String> courses = new LinkedHashMap<>();
    private final Map<String, String> pendingUsers = new LinkedHashMap<>();
    private final Map<String, String> pendingPosts = new LinkedHashMap<>();
    private final Map<String, String> pendingPapers = new LinkedHashMap<>();

    private String conversationId;
    private String communityId;
    private Path selectedChatMedia;
    private Timeline approvalPolling;
    private Timeline chatPolling;
    private AutoCloseable realtimeSubscription;
    private Dialog<Void> activePostDialog;
    private String activePostId;

    @FXML private Label welcome;
    @FXML private Label feedStatus;
    @FXML private Label feedAvatar;
    @FXML private Label chatWith;
    @FXML private Label chatStatus;
    @FXML private Label chatAttachmentStatus;
    @FXML private Label communityName;
    @FXML private Label profileDetails;
    @FXML private Label profileInitial;
    @FXML private Label profileName;
    @FXML private Label profilePhotoStatus;
    @FXML private Label paperStatus;
    @FXML private Label approvalSummary;
    @FXML private Label serverLabel;
    @FXML private ImageView profileImage;
    @FXML private TextArea noticeBody;
    @FXML private TextField noticeTitle;
    @FXML private TextField paperTitle;
    @FXML private TextField examYear;
    @FXML private TextField paperSearch;
    @FXML private TextField messageText;
    @FXML private TextField decisionReason;
    @FXML private TextField chatSearch;
    @FXML private TextField communityText;
    @FXML private ListView<String> noticeList;
    @FXML private ListView<String> paperList;
    @FXML private ListView<String> messageList;
    @FXML private ListView<String> communityList;
    @FXML private ListView<String> userApprovalList;
    @FXML private ListView<String> postApprovalList;
    @FXML private ListView<String> paperApprovalList;
    @FXML private ListView<String> chatUserList;
    @FXML private ListView<String> conversationList;
    @FXML private ComboBox<String> noticeType;
    @FXML private ComboBox<String> courseBox;
    @FXML private Button openPostComposer;
    @FXML private Button publishNoticeButton;
    @FXML private Button uploadPaperButton;
    @FXML private Tab approvalsTab;
    @FXML private TabPane mainTabs;
    @FXML private VBox feedContainer;
    @FXML private VBox noticeComposer;
    @FXML private HBox communityComposer;
    @FXML private ScrollPane feedScroll;

    public ShellController(AppContext context) {
        this.context = context;
        this.realtimeSubscription = context.approvals().subscribe(() -> Platform.runLater(() -> {
            if (messageList != null) {
                refreshConversations();
                refreshMessages();
                loadCommunity();
            }
        }));
    }

    @FXML
    private void initialize() {
        var user = context.session().current().user();
        String identity = user.studentId() == null ? user.loginId() : user.studentId();
        welcome.setText(user.nickname() + " · " + identity);
        serverLabel.setText("● Connected");
        profileInitial.setText(initials(user.nickname()));
        profileName.setText(user.nickname());
        profileDetails.setText("Student ID  " + value(user.studentId()) + "\nDepartment  " + value(user.department())
                + "\nSection  " + value(user.section()) + "   •   Academic year  " + value(user.academicYear())
                + "\nRole  " + user.role().replace('_', ' '));
        feedAvatar.setText(initials(user.nickname()));
        openPostComposer.setText("What's on your mind, " + user.nickname() + "?");
        openPostComposer.setMaxWidth(Double.MAX_VALUE);
        feedContainer.setFillWidth(true);

        context.api().getBytes("profile/photo").whenComplete((bytes, error) -> {
            if (error == null) {
                Platform.runLater(() -> {
                    profileImage.setImage(new Image(new ByteArrayInputStream(bytes)));
                    profileInitial.setVisible(false);
                });
            }
        });

        noticeType.getItems().setAll("GENERAL", "ACADEMIC", "EXAM", "EVENT", "EMERGENCY");
        noticeType.setValue("GENERAL");
        boolean admin = user.role().equals("SYSTEM_ADMIN");
        publishNoticeButton.setDisable(!admin);
        noticeComposer.setVisible(admin);
        noticeComposer.setManaged(admin);
        if (!admin) {
            mainTabs.getTabs().remove(approvalsTab);
        }

        communityComposer.setVisible(user.department() != null);
        communityComposer.setManaged(user.department() != null);
        chatUserList.getSelectionModel().selectedItemProperty().addListener((ignored, oldValue, newValue) -> openConversation());
        conversationList.getSelectionModel().selectedItemProperty().addListener((ignored, oldValue, newValue) -> openSelectedConversation());

        refreshFeed();
        refreshNotices();
        refreshPapers();
        loadUsers();
        refreshConversations();
        if (user.department() != null) {
            refreshCommunity();
        } else {
            communityName.setText("Community unavailable for this account");
        }

        chatPolling = new Timeline(new KeyFrame(Duration.seconds(3), event -> {
            refreshConversations();
            refreshMessages();
            loadCommunity();
        }));
        chatPolling.setCycleCount(Timeline.INDEFINITE);
        chatPolling.play();
        if (admin) {
            refreshApprovals();
            realtimeSubscription = context.approvals().subscribe(() -> Platform.runLater(this::refreshApprovals));
            approvalPolling = new Timeline(new KeyFrame(Duration.seconds(10), event -> refreshApprovals()));
            approvalPolling.setCycleCount(Timeline.INDEFINITE);
            approvalPolling.play();
        }
    }

    // Social feed -------------------------------------------------------------------------------

    @FXML
    private void refreshFeed() {
        run(context.api().getJson("posts/feed"), posts -> {
            feedContainer.getChildren().clear();
            int count = 0;
            for (JsonNode post : posts) {
                feedContainer.getChildren().add(buildPostCard(post, true));
                count++;
            }
            if (count == 0) {
                feedContainer.getChildren().add(emptyFeed());
                feedStatus.setText("No posts yet — start your campus feed.");
            } else {
                feedStatus.setText(count + (count == 1 ? " post in your feed" : " posts in your feed"));
            }
        });
    }

    @FXML
    private void openPostComposer() {
        showPostComposer(false);
    }

    @FXML
    private void openPostComposerWithMedia() {
        showPostComposer(true);
    }

    private void showPostComposer(boolean chooseMediaImmediately) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Create post");
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("create-post-dialog");
        pane.setHeaderText(null);
        pane.setPrefWidth(610);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dialog-titlebar");
        Label title = new Label("Create post");
        title.getStyleClass().add("dialog-title");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("dialog-close");
        close.setOnAction(event -> dialog.close());
        header.getChildren().addAll(title, titleSpacer, close);

        var current = context.session().current().user();
        HBox identity = new HBox(10);
        identity.setAlignment(Pos.CENTER_LEFT);
        Label avatar = avatar(current.nickname(), "dialog-avatar");
        VBox identityText = new VBox(2);
        Label name = new Label(current.nickname());
        name.getStyleClass().add("post-author");
        Label id = new Label("ID: " + (current.studentId() == null ? current.loginId() : current.studentId()));
        id.getStyleClass().add("post-meta");
        identityText.getChildren().addAll(name, id);
        identity.getChildren().addAll(avatar, identityText);

        TextArea body = new TextArea();
        body.setPromptText("What's on your mind?");
        body.setWrapText(true);
        body.setPrefRowCount(6);
        body.getStyleClass().add("post-composer-body");

        final Path[] selectedMedia = new Path[1];
        Label attachment = new Label("No photo or video selected");
        attachment.getStyleClass().add("attachment-name");
        Button addMedia = new Button("Add photo or video");
        addMedia.getStyleClass().add("add-media-button");
        Button removeMedia = new Button("Remove");
        removeMedia.getStyleClass().add("remove-media-button");
        removeMedia.setVisible(false);
        removeMedia.setManaged(false);
        HBox attachmentRow = new HBox(10, addMedia, attachment, removeMedia);
        attachmentRow.setAlignment(Pos.CENTER_LEFT);
        attachmentRow.getStyleClass().add("attachment-row");
        HBox.setHgrow(attachment, Priority.ALWAYS);

        Runnable updateAttachment = () -> {
            boolean hasMedia = selectedMedia[0] != null;
            attachment.setText(hasMedia ? selectedMedia[0].getFileName().toString() : "No photo or video selected");
            removeMedia.setVisible(hasMedia);
            removeMedia.setManaged(hasMedia);
        };
        addMedia.setOnAction(event -> {
            Path file = choosePostMedia(dialog);
            if (file != null) {
                selectedMedia[0] = file;
                updateAttachment.run();
            }
        });
        removeMedia.setOnAction(event -> {
            selectedMedia[0] = null;
            updateAttachment.run();
        });

        Label error = new Label();
        error.getStyleClass().add("composer-error");
        error.setWrapText(true);
        Button publish = new Button("Post");
        publish.setMaxWidth(Double.MAX_VALUE);
        publish.getStyleClass().add("post-submit-button");
        publish.setOnAction(event -> {
            String text = body.getText().trim();
            if (text.isBlank() && selectedMedia[0] == null) {
                error.setText("Write something or add a photo or video first.");
                return;
            }
            publish.setDisable(true);
            error.setText("");
            publishPost(text, selectedMedia[0], created -> {
                dialog.close();
                feedStatus.setText("PENDING".equals(created.path("status").asText())
                        ? "Your post was submitted for review." : "Your post is now in the feed.");
                refreshFeed();
            }, problem -> {
                publish.setDisable(false);
                error.setText(problem);
            });
        });

        VBox content = new VBox(14, header, identity, body, attachmentRow, error, publish);
        content.getStyleClass().add("create-post-content");
        pane.setContent(content);
        dialog.setOnShown(event -> {
            body.requestFocus();
            if (chooseMediaImmediately) {
                Platform.runLater(addMedia::fire);
            }
        });
        dialog.show();
    }

    private Path choosePostMedia(Dialog<?> dialog) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose an image or video");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Photos & videos", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.mp4", "*.mov", "*.webm"));
        Window owner = dialog.getDialogPane().getScene() == null ? null : dialog.getDialogPane().getScene().getWindow();
        File file = chooser.showOpenDialog(owner);
        return file == null ? null : file.toPath();
    }

    private void publishPost(String body, Path media, Consumer<JsonNode> success, Consumer<String> failure) {
        var user = context.session().current().user();
        Map<String, String> request = new LinkedHashMap<>();
        request.put("body", body);
        request.put("department", valueOrEmpty(user.department()));
        request.put("section", valueOrEmpty(user.section()));
        request.put("academicYear", valueOrEmpty(user.academicYear()));
        CompletableFuture<JsonNode> action = media == null
                ? context.api().postJson("posts", request)
                : context.api().uploadFile("posts", request, media, mediaType(media));
        action.whenComplete((created, error) -> Platform.runLater(() -> {
            if (error != null) {
                failure.accept(message(error));
            } else {
                success.accept(created);
            }
        }));
    }

    private VBox emptyFeed() {
        VBox empty = new VBox(6);
        empty.getStyleClass().add("empty-feed");
        Label heading = new Label("Your campus feed is quiet");
        heading.getStyleClass().add("empty-feed-title");
        Label copy = new Label("Be the first to share an update, thought, or photo with your classmates.");
        copy.getStyleClass().add("muted");
        copy.setWrapText(true);
        empty.getChildren().addAll(heading, copy);
        return empty;
    }

    private VBox buildPostCard(JsonNode post, boolean includeActions) {
        VBox card = new VBox(10);
        card.getStyleClass().add("post-card");
        card.setMaxWidth(Double.MAX_VALUE);

        String author = text(post, "authorNickname", "Campus member");
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label authorAvatar = avatar(author, "post-avatar");
        VBox authorDetails = new VBox(2);
        Label authorName = new Label(author);
        authorName.getStyleClass().add("post-author");
        String studentId = text(post, "authorStudentId", "Campus member");
        Label metadata = new Label("ID: " + studentId + "  ·  " + relative(post.path("createdAt").asText()));
        metadata.getStyleClass().add("post-meta");
        authorDetails.getChildren().addAll(authorName, metadata);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        header.getChildren().addAll(authorAvatar, authorDetails, headerSpacer);
        if (post.path("canDelete").asBoolean(false)) {
            Button delete = new Button("Delete");
            delete.getStyleClass().add("post-delete-button");
            delete.setOnAction(event -> requestDeletePost(post));
            header.getChildren().add(delete);
        }
        card.getChildren().add(header);

        String bodyText = text(post, "body", "");
        if (!bodyText.isBlank()) {
            Label body = new Label(bodyText);
            body.setWrapText(true);
            body.setMaxWidth(Double.MAX_VALUE);
            body.getStyleClass().add("post-body");
            card.getChildren().add(body);
        }

        Node media = buildMedia(post, card);
        if (media != null) {
            card.getChildren().add(media);
        }

        if (includeActions) {
            Separator separator = new Separator();
            separator.getStyleClass().add("post-divider");
            HBox actions = new HBox(4);
            actions.setAlignment(Pos.CENTER);
            actions.getStyleClass().add("post-actions");
            actions.getChildren().addAll(
                    reactionButton(post, "LIKE", "Like", post.path("likes").asLong()),
                    reactionButton(post, "DISLIKE", "Dislike", post.path("dislikes").asLong()),
                    commentButton(post));
            card.getChildren().addAll(separator, actions);
        }
        return card;
    }

    private Node buildMedia(JsonNode post, VBox card) {
        JsonNode items = post.path("media");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        VBox media = new VBox(8);
        media.getStyleClass().add("post-media-list");
        for (JsonNode item : items) {
            String contentType = text(item, "contentType", "").toLowerCase();
            if (contentType.startsWith("image/")) {
                media.getChildren().add(imageAttachment(post, item, card));
            } else {
                VBox video = new VBox(3);
                video.getStyleClass().add("video-attachment");
                Label label = new Label("▶ Video attachment");
                label.getStyleClass().add("video-title");
                Label filename = new Label(text(item, "originalName", "Video"));
                filename.getStyleClass().add("post-meta");
                filename.setWrapText(true);
                video.getChildren().addAll(label, filename);
                media.getChildren().add(video);
            }
        }
        return media;
    }

    private StackPane imageAttachment(JsonNode post, JsonNode item, VBox card) {
        StackPane frame = new StackPane();
        frame.getStyleClass().add("image-attachment");
        frame.setMinHeight(170);
        frame.setMaxWidth(Double.MAX_VALUE);
        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitHeight(460);
        imageView.fitWidthProperty().bind(card.widthProperty().subtract(34));
        Label loading = new Label("Loading photo…");
        loading.getStyleClass().add("media-loading");
        frame.getChildren().addAll(imageView, loading);
        String path = "posts/" + post.path("id").asText() + "/media/" + item.path("id").asText();
        context.api().getBytes(path).whenComplete((bytes, error) -> Platform.runLater(() -> {
            if (error != null) {
                loading.setText("Photo unavailable");
                return;
            }
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError()) {
                loading.setText("Photo unavailable");
                return;
            }
            imageView.setImage(image);
            loading.setVisible(false);
            loading.setManaged(false);
        }));
        return frame;
    }

    private Button reactionButton(JsonNode post, String type, String label, long count) {
        boolean selected = type.equals(post.path("myReaction").asText());
        Button button = new Button(label + (count > 0 ? "  " + count : ""));
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("post-action-button");
        button.getStyleClass().add(type.equals("LIKE") ? "like-action" : "dislike-action");
        if (selected) {
            button.getStyleClass().add("action-selected");
        }
        HBox.setHgrow(button, Priority.ALWAYS);
        button.setOnAction(event -> reactToPost(post, type));
        return button;
    }

    private Button commentButton(JsonNode post) {
        long count = post.path("comments").asLong();
        Button button = new Button("Comment" + (count > 0 ? "  " + count : ""));
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().addAll("post-action-button", "comment-action");
        HBox.setHgrow(button, Priority.ALWAYS);
        button.setOnAction(event -> openPostComments(post));
        return button;
    }

    private void reactToPost(JsonNode post, String type) {
        String id = post.path("id").asText();
        CompletableFuture<?> action = type.equals(post.path("myReaction").asText())
                ? context.api().delete("posts/" + id + "/reaction")
                : context.api().putJson("posts/" + id + "/reaction", Map.of("type", type));
        run(action, ignored -> refreshFeed());
    }

    private void requestDeletePost(JsonNode post) {
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete this post? Its comments will no longer be visible.", ButtonType.CANCEL, delete);
        prompt.setTitle("Delete post");
        prompt.setHeaderText(null);
        prompt.showAndWait().filter(choice -> choice == delete).ifPresent(choice -> {
            String id = post.path("id").asText();
            run(context.api().delete("posts/" + id), ignored -> {
                if (id.equals(activePostId) && activePostDialog != null) {
                    activePostDialog.close();
                }
                feedStatus.setText("Post deleted.");
                refreshFeed();
            });
        });
    }

    private void openPostComments(JsonNode post) {
        if (activePostDialog != null && activePostDialog.isShowing()) {
            activePostDialog.getDialogPane().requestFocus();
            return;
        }
        String postId = post.path("id").asText();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(text(post, "authorNickname", "Post") + "'s post");
        dialog.setResizable(true);
        activePostDialog = dialog;
        activePostId = postId;
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("post-dialog");
        pane.setHeaderText(null);
        pane.setPrefWidth(680);
        pane.setPrefHeight(700);

        HBox titlebar = new HBox(10);
        titlebar.setAlignment(Pos.CENTER_LEFT);
        titlebar.getStyleClass().add("dialog-titlebar");
        Label title = new Label("Comments");
        title.getStyleClass().add("dialog-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("dialog-close");
        close.setOnAction(event -> dialog.close());
        titlebar.getChildren().addAll(title, spacer, close);

        VBox discussion = new VBox(12);
        discussion.getStyleClass().add("discussion-content");
        discussion.getChildren().add(buildPostCard(post, false));
        Separator divider = new Separator();
        Label commentsTitle = new Label("Comments");
        commentsTitle.getStyleClass().add("comments-title");
        VBox comments = new VBox(10);
        comments.getStyleClass().add("comments-list");
        discussion.getChildren().addAll(divider, commentsTitle, comments);

        ScrollPane scroll = new ScrollPane(discussion);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("discussion-scroll");

        TextField commentText = new TextField();
        commentText.setPromptText("Write a comment…");
        commentText.getStyleClass().add("comment-input");
        Button postComment = new Button("Comment");
        postComment.getStyleClass().add("comment-submit-button");
        HBox composer = new HBox(8, avatar(context.session().current().user().nickname(), "comment-composer-avatar"), commentText, postComment);
        composer.setAlignment(Pos.CENTER_LEFT);
        composer.getStyleClass().add("comment-composer");
        HBox.setHgrow(commentText, Priority.ALWAYS);

        Runnable submit = () -> submitComment(postId, commentText, comments, commentsTitle, postComment);
        postComment.setOnAction(event -> submit.run());
        commentText.setOnAction(event -> submit.run());

        BorderPane content = new BorderPane();
        content.setTop(titlebar);
        content.setCenter(scroll);
        content.setBottom(composer);
        pane.setContent(content);
        dialog.setOnShown(event -> {
            refreshDialogComments(postId, comments, commentsTitle);
            commentText.requestFocus();
        });
        dialog.setOnHidden(event -> {
            if (activePostDialog == dialog) {
                activePostDialog = null;
                activePostId = null;
            }
        });
        dialog.show();
    }

    private void submitComment(String postId, TextField field, VBox comments, Label commentsTitle, Button submit) {
        String body = field.getText().trim();
        if (body.isBlank()) {
            return;
        }
        submit.setDisable(true);
        run(context.api().postJson("posts/" + postId + "/comments", Map.of("body", body)), created -> {
            field.clear();
            submit.setDisable(false);
            refreshDialogComments(postId, comments, commentsTitle);
            refreshFeed();
        });
    }

    private void refreshDialogComments(String postId, VBox comments, Label commentsTitle) {
        run(context.api().getJson("posts/" + postId + "/comments"), items -> {
            comments.getChildren().clear();
            int count = 0;
            for (JsonNode comment : items) {
                comments.getChildren().add(buildComment(comment, postId, comments, commentsTitle));
                count++;
            }
            commentsTitle.setText(count == 0 ? "Comments" : count + (count == 1 ? " comment" : " comments"));
            if (count == 0) {
                Label empty = new Label("No comments yet. Start the conversation.");
                empty.getStyleClass().add("muted");
                comments.getChildren().add(empty);
            }
        });
    }

    private HBox buildComment(JsonNode comment, String postId, VBox comments, Label commentsTitle) {
        String author = text(comment, "authorNickname", "Campus member");
        HBox row = new HBox(9);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("comment-row");
        Label avatar = avatar(author, "comment-avatar");
        VBox bubble = new VBox(4);
        bubble.setMaxWidth(Double.MAX_VALUE);
        bubble.getStyleClass().add("comment-bubble");
        HBox header = new HBox(7);
        header.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(author);
        name.getStyleClass().add("comment-author");
        Label metadata = new Label("ID: " + text(comment, "authorStudentId", "Campus member") + "  ·  " + relative(comment.path("createdAt").asText()));
        metadata.getStyleClass().add("comment-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(name, metadata, spacer);
        if (comment.path("canDelete").asBoolean(false)) {
            Button delete = new Button("Delete");
            delete.getStyleClass().add("comment-delete-button");
            delete.setOnAction(event -> requestDeleteComment(comment, postId, comments, commentsTitle));
            header.getChildren().add(delete);
        }
        Label body = new Label(text(comment, "body", ""));
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("comment-body");
        bubble.getChildren().addAll(header, body);
        HBox.setHgrow(bubble, Priority.ALWAYS);
        row.getChildren().addAll(avatar, bubble);
        return row;
    }

    private void requestDeleteComment(JsonNode comment, String postId, VBox comments, Label commentsTitle) {
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION, "Delete this comment?", ButtonType.CANCEL, delete);
        prompt.setTitle("Delete comment");
        prompt.setHeaderText(null);
        prompt.showAndWait().filter(choice -> choice == delete).ifPresent(choice -> run(
                context.api().delete("comments/" + comment.path("id").asText()), ignored -> {
                    refreshDialogComments(postId, comments, commentsTitle);
                    refreshFeed();
                }));
    }

    private Label avatar(String name, String styleClass) {
        Label avatar = new Label(initials(name));
        avatar.getStyleClass().add(styleClass);
        return avatar;
    }

    // Notices, papers, chat, community, and profile ------------------------------------------------

    @FXML
    private void refreshNotices() {
        run(context.api().getJson("notices"), json -> {
            noticeList.getItems().clear();
            for (JsonNode notice : json) {
                noticeList.getItems().add("[" + notice.path("type").asText() + "]  " + notice.path("title").asText()
                        + "\n" + notice.path("body").asText() + "\nFor " + notice.path("department").asText("all students"));
            }
        });
    }

    @FXML
    private void publishNotice() {
        if (noticeTitle.getText().isBlank() || noticeBody.getText().isBlank()) {
            return;
        }
        run(context.api().postJson("notices", Map.of("title", noticeTitle.getText(), "body", noticeBody.getText(), "type", noticeType.getValue())), created -> {
            noticeTitle.clear();
            noticeBody.clear();
            refreshNotices();
        });
    }

    @FXML
    private void refreshPapers() {
        run(context.api().getJson("question-papers?q=" + URLEncoder.encode(paperSearch.getText(), StandardCharsets.UTF_8)), json -> {
            paperList.getItems().clear();
            paperIds.clear();
            paperFiles.clear();
            for (JsonNode paper : json) {
                String line = paper.path("courseCode").asText() + "  ·  " + paper.path("department").asText()
                        + "\n" + paper.path("title").asText() + "  (" + paper.path("examYear").asInt() + ")"
                        + "\nPDF · " + paper.path("filename").asText();
                paperList.getItems().add(line);
                paperIds.put(line, paper.path("id").asText());
                paperFiles.put(line, safeFilename(paper.path("filename").asText("question.pdf")));
            }
            paperStatus.setText(json.size() + " curated papers available");
        });
        loadCourses();
    }

    private void loadCourses() {
        run(context.api().getJson("question-papers/courses"), json -> {
            courses.clear();
            courseBox.getItems().clear();
            for (JsonNode course : json) {
                String label = course.path("code").asText() + " — " + course.path("name").asText();
                courses.put(label, course.path("id").asText());
                courseBox.getItems().add(label);
            }
        });
    }

    @FXML
    private void uploadPaper() {
        if (courseBox.getValue() == null || paperTitle.getText().isBlank()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
        File file = chooser.showOpenDialog(courseBox.getScene().getWindow());
        if (file == null) {
            return;
        }
        String year = examYear.getText().isBlank() ? String.valueOf(Year.now().getValue()) : examYear.getText();
        run(context.api().uploadPdf("question-papers", Map.of("courseId", courses.get(courseBox.getValue()), "title", paperTitle.getText(), "examYear", year), file.toPath()), uploaded -> {
            paperTitle.clear();
            paperStatus.setText("PENDING".equals(uploaded.path("status").asText()) ? "Submitted for review." : "Paper uploaded.");
            refreshPapers();
        });
    }

    @FXML
    private void downloadPaper() {
        String line = paperList.getSelectionModel().getSelectedItem();
        String id = paperIds.get(line);
        if (id == null) {
            paperStatus.setText("Select a paper first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(paperFiles.get(line));
        File file = chooser.showSaveDialog(paperList.getScene().getWindow());
        if (file != null) {
            run(context.api().download("question-papers/" + id + "/download", file.toPath()), path -> paperStatus.setText("Saved to " + path.getFileName()));
        }
    }

    private void loadUsers() {
        run(context.api().getJson("chat/users"), json -> {
            users.clear();
            for (JsonNode user : json) {
                String label = user.path("nickname").asText() + "\n" + user.path("loginId").asText() + " · " + user.path("department").asText("");
                users.put(label, user.path("loginId").asText());
            }
            filterChatUsers();
        });
    }

    @FXML
    private void searchChatUsers(KeyEvent ignored) {
        filterChatUsers();
    }

    private void filterChatUsers() {
        String query = chatSearch == null ? "" : chatSearch.getText().toLowerCase();
        chatUserList.getItems().setAll(users.keySet().stream().filter(item -> item.toLowerCase().contains(query)).toList());
    }

    @FXML
    private void openConversation() {
        String id = selected(users, chatUserList);
        if (id == null) {
            return;
        }
        run(context.api().postJson("chat/conversations/with/" + id, Map.of()), json -> {
            conversationId = json.path("id").asText();
            chatWith.setText(json.path("otherNickname").asText());
            chatStatus.setText("Active now");
            refreshConversations();
            refreshMessages();
        });
    }

    private void refreshConversations() {
        run(context.api().getJson("chat/conversations"), json -> {
            String current = conversationId;
            conversations.clear();
            conversationList.getItems().clear();
            for (JsonNode conversation : json) {
                String line = conversation.path("otherNickname").asText() + "\n" + conversation.path("otherLoginId").asText();
                conversations.put(line, conversation.path("id").asText());
                conversationList.getItems().add(line);
                if (conversation.path("id").asText().equals(current)) {
                    conversationList.getSelectionModel().select(line);
                }
            }
        });
    }

    private void openSelectedConversation() {
        String id = selected(conversations, conversationList);
        if (id == null || id.equals(conversationId)) {
            return;
        }
        conversationId = id;
        chatWith.setText(conversationList.getSelectionModel().getSelectedItem().split("\\n")[0]);
        chatStatus.setText("Conversation refreshed automatically");
        refreshMessages();
    }

    private void refreshMessages() {
        if (conversationId == null) {
            return;
        }
        run(context.api().getJson("chat/conversations/" + conversationId + "/messages"), json -> {
            messageList.getItems().clear();
            for (JsonNode message : json) {
                String attachment = message.path("attachmentName").isMissingNode() || message.path("attachmentName").isNull()
                        ? "" : "\n📎 " + message.path("attachmentName").asText();
                messageList.getItems().add(message.path("senderNickname").asText() + "  ·  " + relative(message.path("sentAt").asText())
                        + "\n" + message.path("body").asText() + attachment);
            }
            if (!json.isEmpty()) {
                messageList.scrollTo(json.size() - 1);
            }
        });
    }

    @FXML
    private void chooseChatAttachment() {
        if (conversationId == null) {
            chatStatus.setText("Select a person first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo, video, or file");
        File file = chooser.showOpenDialog(messageText.getScene().getWindow());
        if (file != null) {
            selectedChatMedia = file.toPath();
            chatAttachmentStatus.setText("Ready to send: " + file.getName());
        }
    }

    @FXML
    private void sendMessage() {
        if (conversationId == null || (messageText.getText().isBlank() && selectedChatMedia == null)) {
            return;
        }
        if (selectedChatMedia != null) {
            run(context.api().uploadFile("chat/conversations/" + conversationId + "/messages", Map.of("body", messageText.getText()), selectedChatMedia, mediaType(selectedChatMedia)), sent -> {
                messageText.clear();
                selectedChatMedia = null;
                chatAttachmentStatus.setText("");
                refreshMessages();
                refreshConversations();
            });
        } else {
            run(context.api().postJson("chat/conversations/" + conversationId + "/messages", Map.of("body", messageText.getText())), sent -> {
                messageText.clear();
                refreshMessages();
                refreshConversations();
            });
        }
    }

    @FXML
    private void refreshCommunity() {
        run(context.api().getJson("community/rooms/mine"), room -> {
            communityId = room.path("id").asText();
            communityName.setText(room.path("name").asText());
            loadCommunity();
        });
    }

    private void loadCommunity() {
        if (communityId == null) {
            return;
        }
        run(context.api().getJson("community/rooms/" + communityId + "/messages"), json -> {
            communityList.getItems().clear();
            for (JsonNode message : json) {
                communityList.getItems().add(message.path("authorNickname").asText() + "  ·  " + relative(message.path("createdAt").asText())
                        + "\n" + message.path("body").asText());
            }
        });
    }

    @FXML
    private void sendCommunityMessage() {
        if (communityId == null || communityText.getText().isBlank()) {
            return;
        }
        run(context.api().postJson("community/rooms/" + communityId + "/messages", Map.of("body", communityText.getText())), sent -> {
            communityText.clear();
            loadCommunity();
        });
    }

    @FXML
    private void chooseProfilePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose profile photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Image files", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        File file = chooser.showOpenDialog(profileInitial.getScene().getWindow());
        if (file == null) {
            return;
        }
        run(context.api().uploadFile("profile/photo", Map.of(), file.toPath(), mediaType(file.toPath())), uploaded -> {
            profileImage.setImage(new Image(file.toURI().toString()));
            profileInitial.setVisible(false);
            profilePhotoStatus.setText("Profile photo updated successfully.");
        });
    }

    // Admin approvals -----------------------------------------------------------------------------

    @FXML
    private void refreshApprovals() {
        refreshApprovalSummary();
        loadApprovals("users", userApprovalList, pendingUsers, item -> item.path("studentId").asText() + " — " + item.path("legalName").asText());
        loadApprovals("posts", postApprovalList, pendingPosts, item -> item.path("authorNickname").asText() + "\n" + item.path("body").asText());
        loadApprovals("papers", paperApprovalList, pendingPapers, item -> item.path("courseCode").asText() + " — " + item.path("title").asText());
    }

    private void refreshApprovalSummary() {
        runApproval(context.api().getJson("admin/approvals/summary"), summary -> approvalSummary.setText(summary.path("users").asInt()
                + " students · " + summary.path("posts").asInt() + " posts · " + summary.path("papers").asInt() + " PDFs awaiting review"));
    }

    private void loadApprovals(String kind, ListView<String> list, Map<String, String> ids, java.util.function.Function<JsonNode, String> format) {
        runApproval(context.api().getJson("admin/approvals/" + kind), json -> {
            list.getItems().clear();
            ids.clear();
            for (JsonNode item : json) {
                String line = format.apply(item);
                list.getItems().add(line);
                ids.put(line, item.path("id").asText());
            }
        });
    }

    @FXML private void approveUser() { decide("users", pendingUsers, userApprovalList, "APPROVE"); }
    @FXML private void rejectUser() { decide("users", pendingUsers, userApprovalList, "REJECT"); }
    @FXML private void approvePost() { decide("posts", pendingPosts, postApprovalList, "APPROVE"); }
    @FXML private void rejectPost() { decide("posts", pendingPosts, postApprovalList, "REJECT"); }
    @FXML private void approvePaper() { decide("papers", pendingPapers, paperApprovalList, "APPROVE"); }
    @FXML private void rejectPaper() { decide("papers", pendingPapers, paperApprovalList, "REJECT"); }

    private void decide(String kind, Map<String, String> ids, ListView<String> list, String action) {
        String id = selected(ids, list);
        if (id == null) {
            approvalSummary.setText("Select a request first.");
            return;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", action);
        request.put("reason", decisionReason.getText().isBlank() ? null : decisionReason.getText());
        runApproval(context.api().putJson("admin/approvals/" + kind + "/" + id, request), updated -> {
            decisionReason.clear();
            refreshApprovals();
            if (kind.equals("posts")) {
                refreshFeed();
            }
            if (kind.equals("papers")) {
                refreshPapers();
            }
        });
    }

    @FXML
    private void logout() {
        if (approvalPolling != null) {
            approvalPolling.stop();
        }
        if (chatPolling != null) {
            chatPolling.stop();
        }
        try {
            if (realtimeSubscription != null) {
                realtimeSubscription.close();
            }
        } catch (Exception ignored) {
            // Nothing to do when the socket is already gone.
        }
        context.session().clear();
        context.router().showLogin();
    }

    // Shared helpers ------------------------------------------------------------------------------

    private <T> void run(CompletableFuture<T> task, Consumer<T> success) {
        task.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                if (feedStatus != null) {
                    feedStatus.setText("Error: " + message(error));
                }
            } else {
                success.accept(value);
            }
        }));
    }

    private <T> void runApproval(CompletableFuture<T> task, Consumer<T> success) {
        task.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                approvalSummary.setText("Error: " + message(error));
            } else {
                success.accept(value);
            }
        }));
    }

    private String selected(Map<String, String> ids, ListView<String> list) {
        return ids.get(list.getSelectionModel().getSelectedItem());
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText();
    }

    private String initials(String name) {
        if (name == null || name.isBlank()) {
            return "R";
        }
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, 1).toUpperCase();
        }
        return (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
    }

    private String value(Object value) {
        return value == null ? "Not applicable" : value.toString();
    }

    private String valueOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private String safeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    }

    private String mediaType(Path path) {
        try {
            String type = java.nio.file.Files.probeContentType(path);
            return type == null ? "application/octet-stream" : type;
        } catch (Exception ignored) {
            return "application/octet-stream";
        }
    }

    private String message(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? "Request failed" : cause.getMessage();
    }

    private String relative(String raw) {
        if (raw == null || raw.isBlank()) {
            return "just now";
        }
        try {
            long seconds = Math.max(0, java.time.Duration.between(Instant.parse(raw), Instant.now()).getSeconds());
            if (seconds < 60) return "just now";
            if (seconds < 3_600) return (seconds / 60) + "m";
            if (seconds < 86_400) return (seconds / 3_600) + "h";
            if (seconds < 604_800) return (seconds / 86_400) + "d";
            return (seconds / 604_800) + "w";
        } catch (Exception ignored) {
            return "recently";
        }
    }
}
