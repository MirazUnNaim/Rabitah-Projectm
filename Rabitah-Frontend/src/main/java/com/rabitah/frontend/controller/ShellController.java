package com.rabitah.frontend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabitah.frontend.AppContext;
import com.rabitah.frontend.model.AuthResponse;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
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
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/** The main desktop workspace. Chat polling is a reliable fallback when sockets reconnect. */
public final class ShellController {
    private final AppContext context;
    private final Map<String, String> paperIds = new LinkedHashMap<>();
    private final Map<String, String> paperFiles = new LinkedHashMap<>();
    private final Map<String, ChatPerson> chatPeople = new LinkedHashMap<>();
    private final Map<String, Image> chatMediaPreviews = new ConcurrentHashMap<>();
    private final Map<String, Image> chatProfilePhotos = new ConcurrentHashMap<>();
    private final Map<String, Image> userAvatarPhotos = new ConcurrentHashMap<>();
    private final Map<String, Path> playableMediaFiles = new ConcurrentHashMap<>();
    private final Set<String> renderedMessageIds = new HashSet<>();
    private final Set<String> renderedCommunityMessageIds = new HashSet<>();
    private final Map<String, String> courses = new LinkedHashMap<>();
    private final Map<String, String> pendingUsers = new LinkedHashMap<>();
    private final Map<String, String> pendingPosts = new LinkedHashMap<>();
    private final Map<String, String> pendingPapers = new LinkedHashMap<>();

    private String conversationId;
    private String selectedChatLoginId;
    private String openingChatLoginId;
    private String renderedConversationId;
    private String communityId;
    private String renderedCommunityId;
    private Path selectedChatMedia;
    private Path selectedCommunityMedia;
    private boolean scrollChatToLatest;
    private boolean scrollCommunityToLatest;
    private Timeline approvalPolling;
    private Timeline chatPolling;
    private AutoCloseable realtimeSubscription;
    private Dialog<Void> activePostDialog;
    private String activePostId;

    private record ReplyTarget(String commentId, String author) {}

    private record ChatPerson(String id, String loginId, String nickname, String department, String section,
                              Integer academicYear, int unreadCount, String lastMessage,
                              String lastMessageAt, boolean hasProfilePhoto, String avatarVersion) {}

    @FXML private Label welcome;
    @FXML private Label feedStatus;
    @FXML private Label feedAvatar;
    @FXML private Label chatWith;
    @FXML private Label chatStatus;
    @FXML private Label chatAttachmentStatus;
    @FXML private Label chatReceiverAvatar;
    @FXML private Label chatProfileAvatar;
    @FXML private Label chatProfileName;
    @FXML private Label chatProfileDetails;
    @FXML private Label chatPeopleCount;
    @FXML private Label sharedMediaCount;
    @FXML private Label sharedMediaEmpty;
    @FXML private Label chatEmptyTitle;
    @FXML private Label chatEmptyCopy;
    @FXML private Label communityName;
    @FXML private Label communityAttachmentStatus;
    @FXML private Label profileDetails;
    @FXML private Label profileInitial;
    @FXML private Label profileName;
    @FXML private Label profilePhotoStatus;
    @FXML private Label paperStatus;
    @FXML private Label approvalSummary;
    @FXML private Label serverLabel;
    @FXML private ImageView profileImage;
    @FXML private ImageView feedAvatarPhoto;
    @FXML private ImageView chatReceiverPhoto;
    @FXML private ImageView chatProfilePhoto;
    @FXML private TextArea noticeBody;
    @FXML private TextField noticeTitle;
    @FXML private TextField paperTitle;
    @FXML private TextField examYear;
    @FXML private TextField paperSearch;
    @FXML private TextField messageText;
    @FXML private TextField decisionReason;
    @FXML private TextField chatSearch;
    @FXML private TextField communityText;
    @FXML private TextField profileNicknameInput;
    @FXML private ListView<String> noticeList;
    @FXML private ListView<String> paperList;
    @FXML private ListView<String> userApprovalList;
    @FXML private ListView<String> postApprovalList;
    @FXML private ListView<String> paperApprovalList;
    @FXML private ListView<String> chatUserList;
    @FXML private ComboBox<String> noticeType;
    @FXML private ComboBox<String> courseBox;
    @FXML private Button openPostComposer;
    @FXML private Button publishNoticeButton;
    @FXML private Button uploadPaperButton;
    @FXML private Button chatAttachButton;
    @FXML private Button chatSendButton;
    @FXML private Button communityAttachButton;
    @FXML private Button communitySendButton;
    @FXML private Tab approvalsTab;
    @FXML private Tab privateChatTab;
    @FXML private TabPane mainTabs;
    @FXML private VBox feedContainer;
    @FXML private VBox messageContainer;
    @FXML private VBox chatEmptyState;
    @FXML private VBox communityMessageContainer;
    @FXML private VBox communityEmptyState;
    @FXML private VBox noticeComposer;
    @FXML private VBox communityComposer;
    @FXML private ScrollPane feedScroll;
    @FXML private ScrollPane messageScroll;
    @FXML private ScrollPane communityScroll;
    @FXML private TilePane sharedMediaGrid;

    public ShellController(AppContext context) {
        this.context = context;
        this.realtimeSubscription = context.approvals().subscribe(() -> Platform.runLater(() -> {
            if (messageContainer != null) {
                refreshChatPeople();
                if (isPrivateChatVisible()) {
                    refreshMessages();
                }
                loadCommunity();
                refreshFeed();
            }
        }));
    }

    @FXML
    private void initialize() {
        var user = context.session().current().user();
        serverLabel.setText("● Connected");
        refreshCurrentProfileUi();
        openPostComposer.setMaxWidth(Double.MAX_VALUE);
        feedContainer.setFillWidth(true);
        feedAvatarPhoto.setClip(new Circle(19, 19, 19));
        feedAvatarPhoto.setVisible(false);
        loadCurrentProfilePhoto();

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
        chatUserList.setCellFactory(ignored -> chatPersonCell());
        messageContainer.setFillWidth(true);
        communityMessageContainer.setFillWidth(true);
        chatReceiverPhoto.setClip(new Circle(21, 21, 21));
        chatProfilePhoto.setClip(new Circle(36, 36, 36));
        setChatComposerEnabled(false);
        clearChatView();
        mainTabs.getSelectionModel().selectedItemProperty().addListener((ignored, oldTab, selectedTab) -> {
            if (selectedTab == privateChatTab) {
                refreshChatPeople();
                refreshMessages();
            }
        });

        refreshFeed();
        refreshNotices();
        refreshPapers();
        refreshChatPeople();
        if (user.department() != null) {
            refreshCommunity();
        } else {
            communityName.setText("Community unavailable for this account");
        }

        chatPolling = new Timeline(new KeyFrame(Duration.seconds(3), event -> {
            refreshChatPeople();
            if (isPrivateChatVisible()) {
                refreshMessages();
            }
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
        preparePopup(dialog);
        dialog.setTitle("Create post");
        dialog.setResizable(false);
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
        close.setOnAction(event -> dismissPopup(dialog));
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
                dismissPopup(dialog);
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
        chooser.setTitle("Choose a photo, video, or media file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All media files", "*.*"));
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
        Node authorAvatar = authorAvatar(post, "authorNickname", "authorId", "authorHasProfilePhoto",
                "authorAvatarVersion", "post-avatar", 44);
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
            if (isImageAttachment(item)) {
                media.getChildren().add(imageAttachment(post, item, card));
            } else {
                VBox video = new VBox(3);
                video.getStyleClass().add("video-attachment");
                Label label = new Label(isVideoAttachment(item) ? "▶ Video attachment" : "⇩ Media attachment");
                label.getStyleClass().add("video-title");
                Label filename = new Label(text(item, "originalName", "Video"));
                filename.getStyleClass().add("post-meta");
                filename.setWrapText(true);
                HBox controls = new HBox(7);
                Button play = new Button(isVideoAttachment(item) ? "Play video" : "Open media");
                play.getStyleClass().add("media-play-button");
                play.setOnAction(event -> openRemoteMedia(postMediaPath(post, item), text(item, "originalName", "media"), feedStatus::setText));
                Button download = new Button("Download");
                download.getStyleClass().add("media-download-button");
                download.setOnAction(event -> downloadRemoteAttachment(postMediaPath(post, item),
                        text(item, "originalName", "media"), feedStatus::setText, feedScroll.getScene().getWindow()));
                controls.getChildren().addAll(play, download);
                video.getChildren().addAll(label, filename, controls);
                media.getChildren().add(video);
            }
        }
        return media;
    }

    private StackPane imageAttachment(JsonNode post, JsonNode item, VBox card) {
        StackPane frame = new StackPane();
        frame.getStyleClass().add("image-attachment");
        frame.setMinHeight(150);
        frame.setMaxWidth(Double.MAX_VALUE);
        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitHeight(390);
        imageView.fitWidthProperty().bind(frame.widthProperty().subtract(16));
        Label loading = new Label("Loading photo…");
        loading.getStyleClass().add("media-loading");
        frame.getChildren().addAll(imageView, loading);
        String path = postMediaPath(post, item);
        context.api().getBytes(path).thenApplyAsync(bytes ->
                new Image(new ByteArrayInputStream(bytes), 1024, 600, true, true), context.executor()).whenComplete((image, error) -> Platform.runLater(() -> {
            if (error != null) {
                loading.setText("Photo unavailable");
                return;
            }
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
        preparePopup(prompt);
        prompt.setTitle("Delete post");
        prompt.setHeaderText(null);
        prompt.showAndWait().filter(choice -> choice == delete).ifPresent(choice -> {
            String id = post.path("id").asText();
            run(context.api().delete("posts/" + id), ignored -> {
                if (id.equals(activePostId) && activePostDialog != null) {
                    dismissPopup(activePostDialog);
                }
                feedStatus.setText("Post deleted.");
                refreshFeed();
            });
        });
    }

    private void openPostComments(JsonNode post) {
        if (activePostDialog != null && activePostDialog.isShowing()) {
            Scene popupScene = activePostDialog.getDialogPane().getScene();
            if (popupScene != null) {
                popupScene.getWindow().requestFocus();
            }
            return;
        }
        String postId = post.path("id").asText();
        Dialog<Void> dialog = new Dialog<>();
        preparePopup(dialog);
        dialog.setTitle(text(post, "authorNickname", "Post") + "'s post");
        dialog.setResizable(false);
        activePostDialog = dialog;
        activePostId = postId;
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("post-dialog");
        pane.setHeaderText(null);
        pane.setPrefWidth(680);
        pane.setPrefHeight(620);

        HBox titlebar = new HBox(10);
        titlebar.setAlignment(Pos.CENTER_LEFT);
        titlebar.getStyleClass().add("dialog-titlebar");
        Label title = new Label("Comments");
        title.getStyleClass().add("dialog-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("dialog-close");
        close.setOnAction(event -> dismissPopup(dialog));
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
        final ReplyTarget[] replyTarget = new ReplyTarget[1];
        Label replyStatus = new Label();
        replyStatus.getStyleClass().add("reply-status");
        Button cancelReply = new Button("Cancel reply");
        cancelReply.getStyleClass().add("cancel-reply-button");
        HBox replyBar = new HBox(8, replyStatus, new Region(), cancelReply);
        replyBar.setAlignment(Pos.CENTER_LEFT);
        replyBar.getStyleClass().add("reply-bar");
        HBox.setHgrow(replyBar.getChildren().get(1), Priority.ALWAYS);
        replyBar.setVisible(false);
        replyBar.setManaged(false);

        HBox composerRow = new HBox(8, avatar(context.session().current().user().nickname(), "comment-composer-avatar"), commentText, postComment);
        composerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(commentText, Priority.ALWAYS);
        VBox composer = new VBox(5, replyBar, composerRow);
        composer.getStyleClass().add("comment-composer");

        Runnable clearReply = () -> {
            replyTarget[0] = null;
            replyStatus.setText("");
            replyBar.setVisible(false);
            replyBar.setManaged(false);
            commentText.setPromptText("Write a comment…");
        };
        Consumer<JsonNode> selectReply = comment -> {
            ReplyTarget target = new ReplyTarget(comment.path("id").asText(), text(comment, "authorNickname", "this member"));
            replyTarget[0] = target;
            replyStatus.setText("Replying to " + target.author());
            replyBar.setVisible(true);
            replyBar.setManaged(true);
            commentText.setPromptText("Write a reply to " + target.author() + "…");
            commentText.requestFocus();
        };
        cancelReply.setOnAction(event -> clearReply.run());

        Runnable submit = () -> submitComment(postId, commentText, comments, commentsTitle, postComment, replyTarget[0], clearReply, selectReply);
        postComment.setOnAction(event -> submit.run());
        commentText.setOnAction(event -> submit.run());

        BorderPane content = new BorderPane();
        content.setTop(titlebar);
        content.setCenter(scroll);
        content.setBottom(composer);
        pane.setContent(content);
        dialog.setOnShown(event -> {
            refreshDialogComments(postId, comments, commentsTitle, selectReply);
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

    private void submitComment(String postId, TextField field, VBox comments, Label commentsTitle, Button submit,
                               ReplyTarget replyTarget, Runnable clearReply, Consumer<JsonNode> selectReply) {
        String body = field.getText().trim();
        if (body.isBlank()) {
            return;
        }
        submit.setDisable(true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", body);
        if (replyTarget != null) {
            request.put("parentCommentId", replyTarget.commentId());
        }
        context.api().postJson("posts/" + postId + "/comments", request).whenComplete((created, error) -> Platform.runLater(() -> {
            submit.setDisable(false);
            if (error != null) {
                feedStatus.setText("Could not post the comment: " + message(error));
                return;
            }
            field.clear();
            clearReply.run();
            refreshDialogComments(postId, comments, commentsTitle, selectReply);
            refreshFeed();
        }));
    }

    private void refreshDialogComments(String postId, VBox comments, Label commentsTitle, Consumer<JsonNode> selectReply) {
        run(context.api().getJson("posts/" + postId + "/comments"), items -> {
            comments.getChildren().clear();
            List<JsonNode> allComments = new ArrayList<>();
            Map<String, JsonNode> commentsById = new LinkedHashMap<>();
            for (JsonNode comment : items) {
                allComments.add(comment);
                commentsById.put(comment.path("id").asText(), comment);
            }
            Map<String, List<JsonNode>> repliesByParent = new LinkedHashMap<>();
            List<JsonNode> rootComments = new ArrayList<>();
            for (JsonNode comment : allComments) {
                JsonNode parent = comment.path("parentCommentId");
                String parentId = parent.isMissingNode() || parent.isNull() ? "" : parent.asText();
                if (!parentId.isBlank() && commentsById.containsKey(parentId)) {
                    repliesByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(comment);
                } else {
                    rootComments.add(comment);
                }
            }
            for (JsonNode comment : rootComments) {
                comments.getChildren().add(buildCommentThread(comment, repliesByParent, postId, comments, commentsTitle, selectReply, 0));
            }
            int count = allComments.size();
            commentsTitle.setText(count == 0 ? "Comments" : count + (count == 1 ? " comment" : " comments"));
            if (count == 0) {
                Label empty = new Label("No comments yet. Start the conversation.");
                empty.getStyleClass().add("muted");
                comments.getChildren().add(empty);
            }
        });
    }

    private VBox buildCommentThread(JsonNode comment, Map<String, List<JsonNode>> repliesByParent, String postId,
                                    VBox comments, Label commentsTitle, Consumer<JsonNode> selectReply, int depth) {
        VBox thread = new VBox(6);
        thread.getStyleClass().add("comment-thread");
        if (depth > 0) {
            thread.getStyleClass().add("reply-thread");
        }
        thread.getChildren().add(buildComment(comment, postId, comments, commentsTitle, selectReply));
        for (JsonNode reply : repliesByParent.getOrDefault(comment.path("id").asText(), List.of())) {
            thread.getChildren().add(buildCommentThread(reply, repliesByParent, postId, comments, commentsTitle, selectReply, depth + 1));
        }
        return thread;
    }

    private HBox buildComment(JsonNode comment, String postId, VBox comments, Label commentsTitle, Consumer<JsonNode> selectReply) {
        String author = text(comment, "authorNickname", "Campus member");
        HBox row = new HBox(9);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("comment-row");
        Node avatar = authorAvatar(comment, "authorNickname", "authorId", "authorHasProfilePhoto",
                "authorAvatarVersion", "comment-avatar", 32);
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
            delete.setOnAction(event -> requestDeleteComment(comment, postId, comments, commentsTitle, selectReply));
            header.getChildren().add(delete);
        }
        Label body = new Label(text(comment, "body", ""));
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("comment-body");
        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("comment-actions");
        actions.getChildren().addAll(
                commentReactionButton(comment, postId, comments, commentsTitle, selectReply, "LIKE", "Like", comment.path("likes").asLong()),
                commentReactionButton(comment, postId, comments, commentsTitle, selectReply, "DISLIKE", "Dislike", comment.path("dislikes").asLong()),
                commentReplyButton(comment, selectReply));
        bubble.getChildren().addAll(header, body, actions);
        HBox.setHgrow(bubble, Priority.ALWAYS);
        row.getChildren().addAll(avatar, bubble);
        return row;
    }

    private Button commentReactionButton(JsonNode comment, String postId, VBox comments, Label commentsTitle,
                                         Consumer<JsonNode> selectReply, String type, String label, long count) {
        Button button = new Button(label + (count > 0 ? " " + count : ""));
        button.getStyleClass().addAll("comment-action-button", type.equals("LIKE") ? "like-action" : "dislike-action");
        if (type.equals(comment.path("myReaction").asText())) {
            button.getStyleClass().add("action-selected");
        }
        button.setOnAction(event -> reactToComment(comment, postId, comments, commentsTitle, selectReply, type));
        return button;
    }

    private Button commentReplyButton(JsonNode comment, Consumer<JsonNode> selectReply) {
        Button reply = new Button("Reply");
        reply.getStyleClass().addAll("comment-action-button", "reply-action");
        reply.setOnAction(event -> selectReply.accept(comment));
        return reply;
    }

    private void reactToComment(JsonNode comment, String postId, VBox comments, Label commentsTitle,
                                Consumer<JsonNode> selectReply, String type) {
        String commentId = comment.path("id").asText();
        CompletableFuture<?> action = type.equals(comment.path("myReaction").asText())
                ? context.api().delete("comments/" + commentId + "/reaction")
                : context.api().putJson("comments/" + commentId + "/reaction", Map.of("type", type));
        run(action, ignored -> {
            refreshDialogComments(postId, comments, commentsTitle, selectReply);
            refreshFeed();
        });
    }

    private void requestDeleteComment(JsonNode comment, String postId, VBox comments, Label commentsTitle,
                                      Consumer<JsonNode> selectReply) {
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION, "Delete this comment?", ButtonType.CANCEL, delete);
        preparePopup(prompt);
        prompt.setTitle("Delete comment");
        prompt.setHeaderText(null);
        prompt.showAndWait().filter(choice -> choice == delete).ifPresent(choice -> run(
                context.api().delete("comments/" + comment.path("id").asText()), ignored -> {
                    refreshDialogComments(postId, comments, commentsTitle, selectReply);
                    refreshFeed();
                }));
    }

    private Label avatar(String name, String styleClass) {
        Label avatar = new Label(initials(name));
        avatar.getStyleClass().add(styleClass);
        return avatar;
    }

    private Node authorAvatar(JsonNode author, String nameField, String idField, String photoField,
                              String versionField, String styleClass, double size) {
        return userAvatar(text(author, nameField, "Campus member"), author.path(idField).asText(),
                author.path(photoField).asBoolean(false), nullableText(author, versionField), styleClass, size);
    }

    /** Renders initials immediately, then replaces them with the current profile photo when available. */
    private StackPane userAvatar(String name, String userId, boolean hasPhoto, String avatarVersion,
                                 String styleClass, double size) {
        Label initials = avatar(name, styleClass);
        StackPane holder = new StackPane(initials);
        if (!hasPhoto || userId == null || userId.isBlank()) {
            return holder;
        }
        ImageView photo = new ImageView();
        photo.setFitWidth(size);
        photo.setFitHeight(size);
        photo.setPreserveRatio(true);
        photo.setClip(new Circle(size / 2, size / 2, size / 2));
        photo.setVisible(false);
        holder.getChildren().add(photo);
        String path = avatarPhotoPath(userId, avatarVersion);
        Image cached = userAvatarPhotos.get(path);
        if (cached != null) {
            showAvatarPhoto(photo, initials, cached);
            return holder;
        }
        context.api().getBytes(path).thenApplyAsync(bytes ->
                new Image(new ByteArrayInputStream(bytes), size * 2, size * 2, true, true), context.executor())
                .whenComplete((image, error) -> {
                    if (error == null && image != null && !image.isError()) {
                        userAvatarPhotos.put(path, image);
                        Platform.runLater(() -> showAvatarPhoto(photo, initials, image));
                    }
                });
        return holder;
    }

    private void showAvatarPhoto(ImageView photo, Label initials, Image image) {
        photo.setImage(image);
        photo.setVisible(true);
        initials.setVisible(false);
    }

    private String avatarPhotoPath(String userId, String avatarVersion) {
        String version = avatarVersion == null || avatarVersion.isBlank() ? "current" : avatarVersion;
        return "profile/users/" + userId + "/photo?v=" + URLEncoder.encode(version, StandardCharsets.UTF_8);
    }

    private void refreshCurrentProfileUi() {
        var user = context.session().current().user();
        String identity = user.studentId() == null ? user.loginId() : user.studentId();
        welcome.setText(user.nickname() + " · " + identity);
        profileInitial.setText(initials(user.nickname()));
        profileName.setText(user.nickname());
        profileNicknameInput.setText(user.nickname());
        profileDetails.setText("Student ID  " + value(user.studentId()) + "\nDepartment  " + value(user.department())
                + "\nSection  " + value(user.section()) + "   •   Academic year  " + value(user.academicYear())
                + "\nRole  " + user.role().replace('_', ' '));
        feedAvatar.setText(initials(user.nickname()));
        openPostComposer.setText("What's on your mind, " + user.nickname() + "?");
    }

    private void loadCurrentProfilePhoto() {
        context.api().getBytes("profile/photo").thenApplyAsync(bytes ->
                new Image(new ByteArrayInputStream(bytes), 160, 160, true, true), context.executor()).whenComplete((image, error) -> {
            if (error == null && image != null && !image.isError()) {
                Platform.runLater(() -> {
                    profileImage.setImage(image);
                    feedAvatarPhoto.setImage(image);
                    feedAvatarPhoto.setVisible(true);
                    profileInitial.setVisible(false);
                    feedAvatar.setVisible(false);
                });
            }
        });
    }

    private void invalidateAvatar(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String marker = "profile/users/" + userId + "/photo";
        userAvatarPhotos.keySet().removeIf(path -> path.startsWith(marker));
        chatProfilePhotos.keySet().removeIf(path -> path.startsWith(marker));
    }

    /** Dialogs use their own JavaFX scene, so explicitly carry over the application theme. */
    private void preparePopup(Dialog<?> popup) {
        if (mainTabs != null && mainTabs.getScene() != null) {
            popup.initOwner(mainTabs.getScene().getWindow());
        }
        // Keep these utility windows non-blocking while avoiding normal resizable app windows.
        popup.initStyle(StageStyle.UTILITY);
        popup.initModality(Modality.NONE);
        if (popup.getDialogPane().getButtonTypes().isEmpty()) {
            // Dialog.close() is only honoured when a close/cancel button type exists.
            // The custom close control below handles the actual UI; this hidden type also
            // makes the operating-system close button behave correctly.
            popup.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Node nativeClose = popup.getDialogPane().lookupButton(ButtonType.CLOSE);
            if (nativeClose != null) {
                nativeClose.setVisible(false);
                nativeClose.setManaged(false);
            }
        }
        popup.setOnShowing(event -> {
            Scene popupScene = popup.getDialogPane().getScene();
            if (popupScene == null) {
                return;
            }
            String stylesheet = getClass().getResource("/com/rabitah/frontend/css/app.css").toExternalForm();
            if (!popupScene.getStylesheets().contains(stylesheet)) {
                popupScene.getStylesheets().add(stylesheet);
            }
        });
    }

    /** Hides the backing window directly so custom dialog chrome always closes reliably. */
    private void dismissPopup(Dialog<?> popup) {
        Scene popupScene = popup.getDialogPane().getScene();
        if (popupScene != null && popupScene.getWindow() != null) {
            popupScene.getWindow().hide();
            return;
        }
        popup.close();
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

    private void refreshChatPeople() {
        context.api().getJson("chat/users").whenComplete((json, error) -> Platform.runLater(() -> {
            if (error != null) {
                chatPeopleCount.setText("Unavailable");
                return;
            }
            String selected = selectedChatLoginId;
            chatPeople.clear();
            for (JsonNode user : json) {
                ChatPerson person = chatPerson(user);
                if (!person.loginId().isBlank()) {
                    chatPeople.put(person.loginId(), person);
                }
            }
            filterChatUsers(selected);
            chatPeopleCount.setText(chatPeople.size() + (chatPeople.size() == 1 ? " member" : " members"));
            if (selected != null && chatPeople.containsKey(selected)) {
                updateChatPerson(chatPeople.get(selected));
            }
        }));
    }

    private ChatPerson chatPerson(JsonNode user) {
        JsonNode year = user.path("academicYear");
        return new ChatPerson(
                user.path("id").asText(),
                user.path("loginId").asText(),
                user.path("nickname").asText("Campus member"),
                user.path("department").asText(""),
                user.path("section").asText(""),
                year.isNumber() ? year.asInt() : null,
                Math.max(0, user.path("unreadCount").asInt()),
                nullableText(user, "lastMessage"),
                nullableText(user, "lastMessageAt"),
                user.path("hasProfilePhoto").asBoolean(false),
                nullableText(user, "avatarVersion"));
    }

    @FXML
    private void searchChatUsers(KeyEvent ignored) {
        filterChatUsers(selectedChatLoginId);
    }

    private void filterChatUsers(String preferredSelection) {
        String query = chatSearch == null ? "" : chatSearch.getText().trim().toLowerCase();
        List<String> matching = chatPeople.values().stream()
                .filter(person -> person.nickname().toLowerCase().contains(query)
                        || person.loginId().toLowerCase().contains(query)
                        || person.department().toLowerCase().contains(query))
                .map(ChatPerson::loginId)
                .toList();
        chatUserList.getItems().setAll(matching);
        if (preferredSelection != null && matching.contains(preferredSelection)) {
            chatUserList.getSelectionModel().select(preferredSelection);
        }
    }

    private ListCell<String> chatPersonCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String loginId, boolean empty) {
                super.updateItem(loginId, empty);
                if (empty || loginId == null || !chatPeople.containsKey(loginId)) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                ChatPerson person = chatPeople.get(loginId);
                Node avatar = userAvatar(person.nickname(), person.id(), person.hasProfilePhoto(), person.avatarVersion(),
                        "chat-person-avatar", 40);
                Label name = new Label(person.nickname());
                name.getStyleClass().add("chat-person-name");
                Label detail = new Label(personDetail(person));
                detail.getStyleClass().add("chat-person-detail");
                detail.setWrapText(true);
                VBox labels = new VBox(2, name, detail);
                HBox.setHgrow(labels, Priority.ALWAYS);
                Label unread = new Label(person.unreadCount() > 0 ? String.valueOf(person.unreadCount()) : "");
                unread.getStyleClass().add("chat-unread-badge");
                unread.setVisible(person.unreadCount() > 0);
                unread.setManaged(person.unreadCount() > 0);
                HBox row = new HBox(10, avatar, labels, unread);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("chat-person-row");
                if (person.unreadCount() > 0) {
                    row.getStyleClass().add("has-unread");
                    name.getStyleClass().add("chat-person-unread");
                    detail.getStyleClass().add("chat-person-unread");
                }
                setText(null);
                setGraphic(row);
            }
        };
    }

    private String personDetail(ChatPerson person) {
        if (person.unreadCount() > 0) {
            return person.unreadCount() + (person.unreadCount() == 1 ? " unread message" : " unread messages");
        }
        if (person.lastMessage() != null && !person.lastMessage().isBlank()) {
            return shorten(person.lastMessage(), 38);
        }
        return person.department().isBlank() ? "No messages yet" : person.department();
    }

    @FXML
    private void openConversation() {
        String loginId = chatUserList.getSelectionModel().getSelectedItem();
        if (loginId == null) {
            return;
        }
        if (loginId.equals(selectedChatLoginId) && conversationId != null) {
            return;
        }
        if (loginId.equals(openingChatLoginId)) {
            return;
        }
        ChatPerson person = chatPeople.get(loginId);
        if (person == null) {
            return;
        }
        selectedChatLoginId = loginId;
        selectedChatMedia = null;
        chatAttachmentStatus.setText("");
        updateChatPerson(person);
        setChatComposerEnabled(true);
        conversationId = null;
        openingChatLoginId = loginId;
        clearConversationThread("Opening your chat with " + person.nickname() + "…");
        String requestedLoginId = loginId;
        context.api().postJson("chat/conversations/with/" + loginId, Map.of()).whenComplete((json, error) -> Platform.runLater(() -> {
            if (!requestedLoginId.equals(selectedChatLoginId)) {
                return;
            }
            openingChatLoginId = null;
            if (error != null) {
                chatStatus.setText("Could not open this chat: " + message(error));
                setChatComposerEnabled(false);
                return;
            }
            conversationId = json.path("id").asText();
            scrollChatToLatest = true;
            refreshMessages();
            refreshChatPeople();
        }));
    }

    @FXML
    private void refreshChat() {
        refreshChatPeople();
        refreshMessages();
    }

    private boolean isPrivateChatVisible() {
        return mainTabs != null && mainTabs.getSelectionModel().getSelectedItem() == privateChatTab;
    }

    private void refreshMessages() {
        if (conversationId == null) {
            return;
        }
        String requestedConversationId = conversationId;
        context.api().getJson("chat/conversations/" + requestedConversationId + "/messages").whenComplete((json, error) -> Platform.runLater(() -> {
            if (!requestedConversationId.equals(conversationId)) {
                return;
            }
            if (error != null) {
                chatAttachmentStatus.setText("Could not refresh messages: " + message(error));
                return;
            }
            renderMessages(json);
        }));
    }

    private void renderMessages(JsonNode messages) {
        boolean changedConversation = !conversationId.equals(renderedConversationId);
        if (changedConversation) {
            renderedConversationId = conversationId;
            renderedMessageIds.clear();
        }
        List<JsonNode> attachments = new ArrayList<>();
        messageContainer.getChildren().clear();
        for (JsonNode message : messages) {
            String messageId = message.path("id").asText();
            boolean animate = !changedConversation && !messageId.isBlank() && renderedMessageIds.add(messageId);
            if (changedConversation && !messageId.isBlank()) {
                renderedMessageIds.add(messageId);
            }
            Node row = messageRow(message);
            messageContainer.getChildren().add(row);
            if (animate) {
                animateNewMessage(row);
            }
            if (hasAttachment(message)) {
                attachments.add(message);
            }
        }
        setChatEmptyState(messages.isEmpty(), messages.isEmpty() ? "No messages yet" : "", messages.isEmpty()
                ? "Say hello or share a photo, video, or file to start this conversation." : "");
        renderSharedMedia(attachments);
        if (changedConversation && !messages.isEmpty()) {
            animateChatThread();
        }
        if (scrollChatToLatest || messageScroll.getVvalue() > .92) {
            Platform.runLater(() -> messageScroll.setVvalue(1.0));
        }
        scrollChatToLatest = false;
    }

    private Node messageRow(JsonNode message) {
        boolean mine = isCurrentUser(message.path("senderLoginId").asText());
        HBox row = new HBox();
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setFillHeight(false);
        row.getStyleClass().add("message-row");
        if (mine) {
            row.getStyleClass().add("mine");
        }

        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("message-bubble");
        if (mine) {
            bubble.getStyleClass().add("mine");
        }
        bubble.setMaxWidth(440);
        Label sender = new Label(mine ? "You" : message.path("senderNickname").asText("Campus member"));
        sender.getStyleClass().add("message-sender");
        if (!mine) {
            bubble.getChildren().add(sender);
        }
        String bodyText = message.path("body").asText("").trim();
        if (!bodyText.isBlank()) {
            Label body = new Label(bodyText);
            body.setWrapText(true);
            body.setMaxWidth(400);
            body.getStyleClass().add("message-body");
            bubble.getChildren().add(body);
        }
        if (hasAttachment(message)) {
            boolean video = isVideoAttachment(message);
            String filename = message.path("attachmentName").asText("Attachment");
            Button attachment = new Button(video ? "▶  Play video" : "⇩  " + filename);
            attachment.getStyleClass().add("message-attachment-button");
            attachment.setOnAction(event -> {
                if (video) {
                    openRemoteMedia(chatAttachmentPath(message), filename, chatAttachmentStatus::setText);
                } else {
                    downloadChatAttachment(message);
                }
            });
            if (video) {
                Button download = new Button("Download");
                download.getStyleClass().add("message-attachment-button");
                download.setOnAction(event -> downloadChatAttachment(message));
                HBox controls = new HBox(5, attachment, download);
                bubble.getChildren().add(controls);
            } else {
                bubble.getChildren().add(attachment);
            }
        }
        Label meta = new Label(relative(message.path("sentAt").asText()));
        meta.getStyleClass().add("message-meta");
        bubble.getChildren().add(meta);
        row.getChildren().add(bubble);
        return row;
    }

    private void animateNewMessage(Node node) {
        node.setOpacity(0);
        node.setTranslateY(12);
        FadeTransition fade = new FadeTransition(Duration.millis(210), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(240), node);
        rise.setFromY(12);
        rise.setToY(0);
        new ParallelTransition(fade, rise).play();
    }

    private void animateChatThread() {
        messageContainer.setOpacity(0);
        messageContainer.setTranslateY(8);
        FadeTransition fade = new FadeTransition(Duration.millis(180), messageContainer);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition settle = new TranslateTransition(Duration.millis(210), messageContainer);
        settle.setFromY(8);
        settle.setToY(0);
        new ParallelTransition(fade, settle).play();
    }

    private void renderSharedMedia(List<JsonNode> attachments) {
        sharedMediaGrid.getChildren().clear();
        boolean empty = attachments.isEmpty();
        sharedMediaGrid.setVisible(!empty);
        sharedMediaGrid.setManaged(!empty);
        sharedMediaEmpty.setVisible(empty);
        sharedMediaEmpty.setManaged(empty);
        sharedMediaCount.setText(empty ? "" : attachments.size() + (attachments.size() == 1 ? " item" : " items"));
        for (JsonNode attachment : attachments) {
            sharedMediaGrid.getChildren().add(sharedMediaTile(attachment));
        }
    }

    private Node sharedMediaTile(JsonNode message) {
        VBox tile = new VBox(5);
        tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("shared-media-tile");
        String type = message.path("attachmentContentType").asText("");
        boolean video = isVideoAttachment(message);
        StackPane preview = new StackPane();
        preview.getStyleClass().add("shared-media-preview");
        Label icon = new Label(isImageAttachment(message) ? "▧" : video ? "▶" : "FILE");
        icon.getStyleClass().add("shared-media-icon");
        preview.getChildren().add(icon);
        if (isImageAttachment(message)) {
            loadSharedMediaPreview(message, preview, icon);
        }
        Label name = new Label(shorten(message.path("attachmentName").asText("Attachment"), 15));
        name.setWrapText(true);
        name.setMaxWidth(70);
        name.setAlignment(Pos.CENTER);
        name.getStyleClass().add("shared-media-name");
        Button action = new Button(video ? "Play" : "Download");
        action.getStyleClass().add("shared-media-download");
        action.setOnAction(event -> {
            if (video) {
                openRemoteMedia(chatAttachmentPath(message), message.path("attachmentName").asText("video"), chatAttachmentStatus::setText);
            } else {
                downloadChatAttachment(message);
            }
        });
        tile.getChildren().addAll(preview, name, action);
        return tile;
    }

    private void loadSharedMediaPreview(JsonNode message, StackPane preview, Label fallback) {
        if (conversationId == null) {
            return;
        }
        String path = chatAttachmentPath(message);
        Image cached = chatMediaPreviews.get(path);
        if (cached != null) {
            showSharedMediaPreview(preview, fallback, cached);
            return;
        }
        context.api().getBytes(path).thenApplyAsync(bytes ->
                new Image(new ByteArrayInputStream(bytes), 100, 100, true, true), context.executor()).whenComplete((image, error) -> {
            if (error == null && image != null && !image.isError()) {
                chatMediaPreviews.put(path, image);
                Platform.runLater(() -> showSharedMediaPreview(preview, fallback, image));
            }
        });
    }

    private void showSharedMediaPreview(StackPane preview, Label fallback, Image image) {
        ImageView view = new ImageView(image);
        view.setFitWidth(72);
        view.setFitHeight(59);
        view.setPreserveRatio(true);
        preview.getChildren().setAll(view);
        fallback.setVisible(false);
    }

    private void downloadChatAttachment(JsonNode message) {
        if (conversationId == null || !hasAttachment(message)) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save shared file");
        chooser.setInitialFileName(message.path("attachmentName").asText("attachment"));
        File target = chooser.showSaveDialog(messageText.getScene().getWindow());
        if (target != null) {
            run(context.api().download(chatAttachmentPath(message), target.toPath()), saved ->
                    chatAttachmentStatus.setText("Saved " + saved.getFileName()));
        }
    }

    private String chatAttachmentPath(JsonNode message) {
        return "chat/conversations/" + conversationId + "/messages/" + message.path("id").asText() + "/attachment";
    }

    private boolean hasAttachment(JsonNode message) {
        return !message.path("attachmentName").isMissingNode()
                && !message.path("attachmentName").isNull()
                && !message.path("attachmentName").asText().isBlank();
    }

    private void updateChatPerson(ChatPerson person) {
        chatWith.setText(person.nickname());
        chatReceiverAvatar.setText(initials(person.nickname()));
        chatProfileAvatar.setText(initials(person.nickname()));
        showChatProfilePhoto(person);
        chatProfileName.setText(person.nickname());
        String department = person.department().isBlank() ? "Campus member" : person.department();
        String section = person.section().isBlank() ? "—" : person.section();
        String year = person.academicYear() == null ? "—" : String.valueOf(person.academicYear());
        chatStatus.setText("ID: " + person.loginId() + "  ·  " + department);
        chatProfileDetails.setText("ID  " + person.loginId() + "\nDepartment  " + department
                + "\nSection  " + section + "   •   Academic year  " + year);
    }

    private void showChatProfilePhoto(ChatPerson person) {
        String path = avatarPhotoPath(person.id(), person.avatarVersion());
        Image cached = person.hasProfilePhoto() ? chatProfilePhotos.get(path) : null;
        if (cached != null) {
            applyChatProfilePhoto(person.loginId(), cached);
            return;
        }
        chatReceiverPhoto.setImage(null);
        chatProfilePhoto.setImage(null);
        chatReceiverPhoto.setVisible(false);
        chatProfilePhoto.setVisible(false);
        chatReceiverAvatar.setVisible(true);
        chatProfileAvatar.setVisible(true);
        if (!person.hasProfilePhoto() || person.id().isBlank()) {
            return;
        }
        context.api().getBytes(path).thenApplyAsync(bytes ->
                new Image(new ByteArrayInputStream(bytes), 144, 144, true, true), context.executor()).whenComplete((image, error) -> {
            if (error == null && image != null && !image.isError()) {
                chatProfilePhotos.put(path, image);
                Platform.runLater(() -> applyChatProfilePhoto(person.loginId(), image));
            }
        });
    }

    private void applyChatProfilePhoto(String loginId, Image image) {
        if (!loginId.equals(selectedChatLoginId)) {
            return;
        }
        chatReceiverPhoto.setImage(image);
        chatProfilePhoto.setImage(image);
        chatReceiverPhoto.setVisible(true);
        chatProfilePhoto.setVisible(true);
        chatReceiverAvatar.setVisible(false);
        chatProfileAvatar.setVisible(false);
    }

    private void clearChatView() {
        chatReceiverAvatar.setText("?");
        chatProfileAvatar.setText("?");
        chatReceiverPhoto.setImage(null);
        chatProfilePhoto.setImage(null);
        chatReceiverPhoto.setVisible(false);
        chatProfilePhoto.setVisible(false);
        chatWith.setText("Select someone to start chatting");
        chatStatus.setText("Choose a campus member to begin.");
        chatProfileName.setText("No conversation selected");
        chatProfileDetails.setText("Choose a person to see their campus profile.");
        clearConversationThread("Your conversations live here");
        renderSharedMedia(List.of());
    }

    private void clearConversationThread(String title) {
        renderedConversationId = null;
        renderedMessageIds.clear();
        messageContainer.getChildren().clear();
        setChatEmptyState(true, title, "Choose a person, then write a message or share media.");
        renderSharedMedia(List.of());
    }

    private void setChatEmptyState(boolean visible, String title, String copy) {
        chatEmptyState.setVisible(visible);
        chatEmptyState.setManaged(visible);
        if (visible) {
            chatEmptyTitle.setText(title);
            chatEmptyCopy.setText(copy);
        }
    }

    private void setChatComposerEnabled(boolean enabled) {
        messageText.setDisable(!enabled);
        chatAttachButton.setDisable(!enabled);
        chatSendButton.setDisable(!enabled);
    }

    private boolean isCurrentUser(String senderLoginId) {
        var user = context.session().current().user();
        String identity = user.studentId() == null ? user.loginId() : user.studentId();
        return identity.equalsIgnoreCase(senderLoginId) || user.loginId().equalsIgnoreCase(senderLoginId);
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String shorten(String value, int length) {
        if (value == null || value.length() <= length) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, length - 1)) + "…";
    }

    @FXML
    private void chooseChatAttachment() {
        if (conversationId == null) {
            chatStatus.setText("Choose a person before attaching media.");
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
        String sendingConversationId = conversationId;
        Path mediaToSend = selectedChatMedia;
        String bodyToSend = messageText.getText();
        scrollChatToLatest = true;
        chatSendButton.setDisable(true);
        messageText.setDisable(true);
        chatAttachButton.setDisable(true);
        CompletableFuture<JsonNode> action = mediaToSend != null
                ? context.api().uploadFile("chat/conversations/" + sendingConversationId + "/messages",
                Map.of("body", bodyToSend), mediaToSend, mediaType(mediaToSend))
                : context.api().postJson("chat/conversations/" + sendingConversationId + "/messages", Map.of("body", bodyToSend));
        action.whenComplete((sent, error) -> Platform.runLater(() -> {
            if (!sendingConversationId.equals(conversationId)) {
                return;
            }
            setChatComposerEnabled(true);
            if (error != null) {
                chatAttachmentStatus.setText("Could not send message: " + message(error));
                return;
            }
            if (mediaToSend != null) {
                messageText.clear();
                if (selectedChatMedia == mediaToSend) {
                    selectedChatMedia = null;
                }
                chatAttachmentStatus.setText("");
            } else {
                messageText.clear();
            }
            refreshMessages();
            refreshChatPeople();
        }));
    }

    @FXML
    private void refreshCommunity() {
        context.api().getJson("community/rooms/mine").whenComplete((room, error) -> Platform.runLater(() -> {
            if (error != null) {
                return;
            }
            communityId = room.path("id").asText();
            communityName.setText(room.path("name").asText());
            loadCommunity();
        }));
    }

    private void loadCommunity() {
        if (communityId == null) {
            return;
        }
        String requestedRoomId = communityId;
        context.api().getJson("community/rooms/" + requestedRoomId + "/messages").whenComplete((json, error) -> Platform.runLater(() -> {
            if (!requestedRoomId.equals(communityId)) {
                return;
            }
            if (error != null) {
                communityAttachmentStatus.setText("Could not refresh community chat: " + message(error));
                return;
            }
            renderCommunityMessages(json);
        }));
    }

    private void renderCommunityMessages(JsonNode messages) {
        boolean roomChanged = !communityId.equals(renderedCommunityId);
        if (roomChanged) {
            renderedCommunityId = communityId;
            renderedCommunityMessageIds.clear();
        }
        communityMessageContainer.getChildren().clear();
        for (JsonNode communityMessage : messages) {
            String id = communityMessage.path("id").asText();
            boolean animate = !roomChanged && !id.isBlank() && renderedCommunityMessageIds.add(id);
            if (roomChanged && !id.isBlank()) {
                renderedCommunityMessageIds.add(id);
            }
            Node row = communityMessageRow(communityMessage);
            communityMessageContainer.getChildren().add(row);
            if (animate) {
                animateNewMessage(row);
            }
        }
        communityEmptyState.setVisible(messages.isEmpty());
        communityEmptyState.setManaged(messages.isEmpty());
        if (scrollCommunityToLatest || communityScroll.getVvalue() > .92) {
            Platform.runLater(() -> communityScroll.setVvalue(1.0));
        }
        scrollCommunityToLatest = false;
    }

    private Node communityMessageRow(JsonNode message) {
        boolean mine = isCurrentUser(message.path("authorLoginId").asText());
        HBox row = new HBox(8);
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setFillHeight(false);
        row.getStyleClass().addAll("message-row", "community-message-row");
        if (mine) {
            row.getStyleClass().add("mine");
        }

        if (!mine) {
            row.getChildren().add(authorAvatar(message, "authorNickname", "authorId", "authorHasProfilePhoto",
                    "authorAvatarVersion", "comment-avatar", 32));
        }
        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("message-bubble");
        if (mine) {
            bubble.getStyleClass().add("mine");
        }
        bubble.setMaxWidth(440);
        if (!mine) {
            Label sender = new Label(text(message, "authorNickname", "Campus member"));
            sender.getStyleClass().add("message-sender");
            bubble.getChildren().add(sender);
        }
        String body = message.path("body").asText("").trim();
        if (!body.isBlank()) {
            Label text = new Label(body);
            text.setWrapText(true);
            text.setMaxWidth(400);
            text.getStyleClass().add("message-body");
            bubble.getChildren().add(text);
        }
        if (hasAttachment(message)) {
            boolean video = isVideoAttachment(message);
            String filename = message.path("attachmentName").asText("Attachment");
            Button attachment = new Button(video ? "▶  Play video" : "⇩  " + filename);
            attachment.getStyleClass().add("message-attachment-button");
            attachment.setOnAction(event -> {
                if (video) {
                    openRemoteMedia(communityAttachmentPath(message), filename,
                            communityAttachmentStatus::setText);
                } else {
                    downloadCommunityAttachment(message);
                }
            });
            if (video) {
                Button download = new Button("Download");
                download.getStyleClass().add("message-attachment-button");
                download.setOnAction(event -> downloadCommunityAttachment(message));
                HBox controls = new HBox(5, attachment, download);
                bubble.getChildren().add(controls);
            } else {
                bubble.getChildren().add(attachment);
            }
        }
        Label metadata = new Label(relative(message.path("createdAt").asText()));
        metadata.getStyleClass().add("message-meta");
        bubble.getChildren().add(metadata);
        row.getChildren().add(bubble);
        return row;
    }

    @FXML
    private void chooseCommunityAttachment() {
        if (communityId == null) {
            communityAttachmentStatus.setText("Community chat is still loading.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo, video, or file");
        File file = chooser.showOpenDialog(communityText.getScene().getWindow());
        if (file != null) {
            selectedCommunityMedia = file.toPath();
            communityAttachmentStatus.setText("Ready to send: " + file.getName());
        }
    }

    @FXML
    private void sendCommunityMessage() {
        if (communityId == null || (communityText.getText().isBlank() && selectedCommunityMedia == null)) {
            return;
        }
        String sendingRoomId = communityId;
        Path mediaToSend = selectedCommunityMedia;
        String bodyToSend = communityText.getText();
        scrollCommunityToLatest = true;
        communitySendButton.setDisable(true);
        communityText.setDisable(true);
        communityAttachButton.setDisable(true);
        CompletableFuture<JsonNode> action = mediaToSend == null
                ? context.api().postJson("community/rooms/" + sendingRoomId + "/messages", Map.of("body", bodyToSend))
                : context.api().uploadFile("community/rooms/" + sendingRoomId + "/messages", Map.of("body", bodyToSend),
                mediaToSend, mediaType(mediaToSend));
        action.whenComplete((sent, error) -> Platform.runLater(() -> {
            if (!sendingRoomId.equals(communityId)) {
                return;
            }
            communitySendButton.setDisable(false);
            communityText.setDisable(false);
            communityAttachButton.setDisable(false);
            if (error != null) {
                communityAttachmentStatus.setText("Could not send message: " + message(error));
                return;
            }
            communityText.clear();
            if (mediaToSend != null && selectedCommunityMedia == mediaToSend) {
                selectedCommunityMedia = null;
            }
            communityAttachmentStatus.setText("");
            loadCommunity();
        }));
    }

    private String communityAttachmentPath(JsonNode message) {
        return "community/rooms/" + communityId + "/messages/" + message.path("id").asText() + "/attachment";
    }

    private void downloadCommunityAttachment(JsonNode message) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save community file");
        chooser.setInitialFileName(message.path("attachmentName").asText("attachment"));
        File target = chooser.showSaveDialog(communityText.getScene().getWindow());
        if (target != null) {
            run(context.api().download(communityAttachmentPath(message), target.toPath()), saved ->
                    communityAttachmentStatus.setText("Saved " + saved.getFileName()));
        }
    }

    @FXML
    private void updateProfileName() {
        String nickname = profileNicknameInput.getText() == null ? "" : profileNicknameInput.getText().trim();
        if (nickname.isBlank()) {
            profilePhotoStatus.setText("Enter a display name first.");
            return;
        }
        profilePhotoStatus.setText("Saving display name…");
        context.api().putJson("profile", Map.of("nickname", nickname)).whenComplete((profile, error) ->
                Platform.runLater(() -> {
                    if (error != null) {
                        profilePhotoStatus.setText("Could not update name: " + message(error));
                        return;
                    }
                    applyCurrentNickname(text(profile, "nickname", nickname));
                    invalidateAvatar(context.session().current().user().id().toString());
                    refreshCurrentProfileUi();
                    profilePhotoStatus.setText("Display name updated.");
                    refreshFeed();
                    refreshChatPeople();
                    loadCommunity();
                }));
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
            Image image = new Image(file.toURI().toString());
            profileImage.setImage(image);
            feedAvatarPhoto.setImage(image);
            feedAvatarPhoto.setVisible(true);
            profileInitial.setVisible(false);
            feedAvatar.setVisible(false);
            invalidateAvatar(context.session().current().user().id().toString());
            profilePhotoStatus.setText("Profile photo updated successfully.");
            refreshFeed();
            refreshChatPeople();
            loadCommunity();
        });
    }

    private void applyCurrentNickname(String nickname) {
        AuthResponse current = context.session().current();
        AuthResponse.UserSummary user = current.user();
        context.session().open(new AuthResponse(current.accessToken(), current.refreshToken(), current.expiresInSeconds(),
                new AuthResponse.UserSummary(user.id(), user.loginId(), user.studentId(), nickname, user.role(),
                        user.department(), user.section(), user.academicYear())));
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

    private String postMediaPath(JsonNode post, JsonNode media) {
        return "posts/" + post.path("id").asText() + "/media/" + media.path("id").asText();
    }

    private boolean isImageAttachment(JsonNode media) {
        String type = attachmentContentType(media);
        return type.startsWith("image/") || hasExtension(attachmentName(media),
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "tif", "tiff", "avif", "heic");
    }

    private boolean isVideoAttachment(JsonNode media) {
        String type = attachmentContentType(media);
        return type.startsWith("video/") || hasExtension(attachmentName(media),
                "mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv", "flv", "f4v", "mpeg", "mpg",
                "mpe", "3gp", "3g2", "ogv", "ts", "mts", "m2ts", "asf", "rm", "rmvb", "divx", "hevc");
    }

    private String attachmentContentType(JsonNode media) {
        String type = text(media, "attachmentContentType", text(media, "contentType", ""));
        return type.toLowerCase();
    }

    private String attachmentName(JsonNode media) {
        return text(media, "attachmentName", text(media, "originalName", "attachment"));
    }

    private boolean hasExtension(String filename, String... extensions) {
        String value = filename == null ? "" : filename.toLowerCase();
        for (String extension : extensions) {
            if (value.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Downloads protected media to a temporary file and asks the operating system's default player
     * to open it. This covers any video format supported by the user's installed player, instead of
     * being limited to JavaFX's small built-in codec set.
     */
    private void openRemoteMedia(String remotePath, String filename, Consumer<String> feedback) {
        Path cached = playableMediaFiles.get(remotePath);
        if (cached != null && java.nio.file.Files.exists(cached)) {
            openLocalMedia(cached, filename, feedback);
            return;
        }
        feedback.accept("Preparing " + filename + "…");
        context.api().getBytes(remotePath).thenApplyAsync(bytes -> {
            try {
                String suffix = fileSuffix(filename);
                Path temporary = java.nio.file.Files.createTempFile("rabitah-media-", suffix);
                java.nio.file.Files.write(temporary, bytes);
                temporary.toFile().deleteOnExit();
                playableMediaFiles.put(remotePath, temporary);
                return temporary;
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Could not create a temporary media file.", error);
            }
        }, context.executor()).whenComplete((file, error) -> Platform.runLater(() -> {
            if (error != null) {
                feedback.accept("Could not prepare media: " + message(error));
                return;
            }
            openLocalMedia(file, filename, feedback);
        }));
    }

    private void openLocalMedia(Path file, String filename, Consumer<String> feedback) {
        CompletableFuture.runAsync(() -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IllegalStateException("No default media player is available. Download the file to open it manually.");
            }
            try {
                Desktop.getDesktop().open(file.toFile());
            } catch (Exception error) {
                throw new IllegalStateException("Could not open " + filename + " with your default media player.", error);
            }
        }, context.executor()).whenComplete((ignored, error) -> Platform.runLater(() -> {
            feedback.accept(error == null ? "Opened " + filename + " in your default media player." : message(error));
        }));
    }

    private void downloadRemoteAttachment(String remotePath, String filename, Consumer<String> feedback, Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save media");
        chooser.setInitialFileName(filename == null || filename.isBlank() ? "media" : filename);
        File target = chooser.showSaveDialog(owner);
        if (target != null) {
            context.api().download(remotePath, target.toPath()).whenComplete((saved, error) -> Platform.runLater(() -> {
                feedback.accept(error == null ? "Saved " + saved.getFileName() : "Could not save media: " + message(error));
            }));
        }
    }

    private String fileSuffix(String filename) {
        if (filename == null) {
            return ".media";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".media";
        }
        String suffix = filename.substring(dot);
        return suffix.length() > 16 ? ".media" : suffix;
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
