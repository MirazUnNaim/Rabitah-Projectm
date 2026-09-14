package com.rabitah.backend.chat;

import com.rabitah.backend.common.ApiException;
import com.rabitah.backend.realtime.ApprovalEvents;
import com.rabitah.backend.security.CurrentUserService;
import com.rabitah.backend.storage.MediaStorage;
import com.rabitah.backend.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    private final ApprovalEvents events;
    private final MediaStorage storage;

    public ChatController(JdbcTemplate jdbc, CurrentUserService current, ApprovalEvents events, MediaStorage storage) {
        this.jdbc = jdbc;
        this.current = current;
        this.events = events;
        this.storage = storage;
    }

    /**
     * The people pane is deliberately user-centric rather than conversation-centric: every active
     * account appears, even before a conversation exists. Nullable last-message fields are expected
     * for people who have never chatted with the signed-in user.
     */
    @GetMapping("/users")
    public List<ChatUser> users(Authentication auth) {
        User me = current.require(auth);
        return jdbc.query("""
                select u.id,
                       coalesce(u.student_id, u.login_id) as login_id,
                       u.nickname,
                       u.department_code,
                       u.section_code,
                       u.academic_year,
                       (u.profile_photo_key is not null) as has_profile_photo,
                       u.updated_at as avatar_version,
                       coalesce((
                           select count(*)
                           from private_messages unread_message
                           where unread_message.conversation_id = c.id
                             and unread_message.sender_id = u.id
                             and unread_message.read_at is null
                       ), 0) as unread_count,
                       left(coalesce(
                           nullif(btrim(last_message.body), ''),
                           case when last_message.original_name is not null
                                then 'Attachment: ' || last_message.original_name end
                       ), 140) as last_message,
                       last_message.sent_at as last_message_at
                from users u
                left join private_conversations c on
                       (c.participant_one = ? and c.participant_two = u.id)
                    or (c.participant_two = ? and c.participant_one = u.id)
                left join lateral (
                    select m.body, m.original_name, m.sent_at
                    from private_messages m
                    where m.conversation_id = c.id
                    order by m.sent_at desc, m.id desc
                    limit 1
                ) last_message on true
                where u.status = 'ACTIVE' and u.id <> ?
                order by unread_count desc, last_message_at desc nulls last, lower(u.nickname)
                """, (rs, rowNum) -> {
            OffsetDateTime lastMessageAt = rs.getObject("last_message_at", OffsetDateTime.class);
            OffsetDateTime avatarVersion = rs.getObject("avatar_version", OffsetDateTime.class);
            return new ChatUser(
                    rs.getObject("id", UUID.class),
                    rs.getString("login_id"),
                    rs.getString("nickname"),
                    rs.getString("department_code"),
                    rs.getString("section_code"),
                    rs.getObject("academic_year", Integer.class),
                    rs.getBoolean("has_profile_photo"),
                    avatarVersion == null ? null : avatarVersion.toInstant(),
                    rs.getLong("unread_count"),
                    rs.getString("last_message"),
                    lastMessageAt == null ? null : lastMessageAt.toInstant());
        }, me.getId(), me.getId(), me.getId());
    }

    /**
     * Returns an avatar only to authenticated campus members, and only for an active account. The
     * storage key stays server-side so it cannot be used to bypass this access check.
     */
    @GetMapping("/users/{id}/photo")
    public ResponseEntity<byte[]> userPhoto(@PathVariable UUID id, Authentication auth) throws IOException {
        current.require(auth);
        List<String> photoKeys = jdbc.query(
                "select profile_photo_key from users where id=? and status='ACTIVE' and profile_photo_key is not null",
                (rs, rowNum) -> rs.getString(1), id);
        if (photoKeys.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_PHOTO_NOT_FOUND", "This user has no profile photo.");
        }
        String key = photoKeys.getFirst();
        return ResponseEntity.ok()
                .contentType(photoContentType(key))
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
                .body(storage.read(key));
    }

    @GetMapping("/conversations")
    public List<Conversation> conversations(Authentication auth) {
        User me = current.require(auth);
        return jdbc.query("""
                select c.id, coalesce(u.student_id, u.login_id), u.nickname, max(m.sent_at) last_at
                from private_conversations c
                join users u on u.id = case when c.participant_one=? then c.participant_two else c.participant_one end
                left join private_messages m on m.conversation_id=c.id
                where c.participant_one=? or c.participant_two=?
                group by c.id, u.student_id, u.login_id, u.nickname
                order by last_at desc nulls last
                """, (rs, rowNum) -> {
            OffsetDateTime lastAt = rs.getObject(4, OffsetDateTime.class);
            return new Conversation(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    lastAt == null ? null : lastAt.toInstant());
        }, me.getId(), me.getId(), me.getId());
    }

    @PostMapping("/conversations/with/{loginId}")
    @Transactional
    public Conversation open(@PathVariable String loginId, Authentication auth) {
        User me = current.require(auth);
        Map<String, Object> other = find(loginId);
        UUID otherId = (UUID) other.get("id");
        String otherChatLoginId = (String) other.get("chat_login_id");
        if (otherId.equals(me.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECIPIENT", "Choose another user");
        }
        jdbc.update("""
                insert into private_conversations(id, participant_one, participant_two)
                select gen_random_uuid(), ?, ?
                where not exists(
                    select 1 from private_conversations
                    where (participant_one=? and participant_two=?) or (participant_one=? and participant_two=?)
                )
                """, me.getId(), otherId, me.getId(), otherId, otherId, me.getId());
        return conversations(auth).stream()
                .filter(conversation -> conversation.otherLoginId().equalsIgnoreCase(otherChatLoginId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Opening a conversation is the read receipt. Because private conversations have exactly two
     * participants, a read timestamp on each message accurately captures the recipient's state and
     * makes unread totals durable across restarts and devices.
     */
    @GetMapping("/conversations/{id}/messages")
    @Transactional
    public List<Message> messages(@PathVariable UUID id, Authentication auth) {
        User me = current.require(auth);
        member(id, me);
        int markedRead = jdbc.update("""
                update private_messages
                set read_at=now()
                where conversation_id=? and sender_id<>? and read_at is null
                """, id, me.getId());
        if (markedRead > 0) {
            events.chatChanged();
        }
        return jdbc.query("""
                select m.id, m.body, m.sent_at, coalesce(u.student_id, u.login_id), u.nickname,
                       m.original_name, m.content_type, m.size_bytes
                from private_messages m
                join users u on u.id=m.sender_id
                where m.conversation_id=?
                order by m.sent_at, m.id
                """, (rs, rowNum) -> message(rs), id);
    }

    @GetMapping("/conversations/{conversationId}/messages/{messageId}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                                       Authentication auth) throws IOException {
        User me = current.require(auth);
        member(conversationId, me);
        List<StoredAttachment> attachments = jdbc.query("""
                select storage_key, original_name, content_type
                from private_messages
                where id=? and conversation_id=? and storage_key is not null
                """, (rs, rowNum) -> new StoredAttachment(rs.getString(1), rs.getString(2), rs.getString(3)),
                messageId, conversationId);
        if (attachments.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_ATTACHMENT_NOT_FOUND", "This message has no attachment.");
        }
        StoredAttachment attachment = attachments.getFirst();
        String filename = attachment.originalName() == null || attachment.originalName().isBlank()
                ? "attachment" : attachment.originalName();
        return ResponseEntity.ok()
                .contentType(contentType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(storage.read(attachment.storageKey()));
    }

    @PostMapping(value = "/conversations/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Message send(@PathVariable UUID id, @Valid @RequestBody SendRequest request, Authentication auth) {
        return persist(id, request.body(), null, auth);
    }

    @PostMapping(value = "/conversations/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Message sendMedia(@PathVariable UUID id, @RequestParam(defaultValue = "") String body,
                             @RequestPart MultipartFile file, Authentication auth) {
        if (file.isEmpty() || file.getSize() > 50_000_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Choose a file smaller than 50 MB.");
        }
        return persist(id, body, file, auth);
    }

    private Message persist(UUID conversationId, String body, MultipartFile file, Authentication auth) {
        User me = current.require(auth);
        member(conversationId, me);
        String text = body == null ? "" : body.trim();
        if (text.isBlank() && file == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "Write a message or attach a file.");
        }
        UUID messageId = UUID.randomUUID();
        String key = null;
        if (file != null) {
            try {
                key = storage.save("chat/" + conversationId, file);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_SAVE_FAILED", "Could not save attachment");
            }
        }
        jdbc.update("""
                insert into private_messages(id, conversation_id, sender_id, client_message_id, body,
                                             storage_key, original_name, content_type, size_bytes)
                values(?,?,?,?,?,?,?,?,?)
                """, messageId, conversationId, me.getId(), UUID.randomUUID(), text, key,
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getContentType(),
                file == null ? null : file.getSize());
        events.chatChanged();
        return message(conversationId, messageId);
    }

    private Message message(UUID conversationId, UUID messageId) {
        List<Message> messages = jdbc.query("""
                select m.id, m.body, m.sent_at, coalesce(u.student_id, u.login_id), u.nickname,
                       m.original_name, m.content_type, m.size_bytes
                from private_messages m
                join users u on u.id=m.sender_id
                where m.conversation_id=? and m.id=?
                """, (rs, rowNum) -> message(rs), conversationId, messageId);
        if (messages.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found");
        }
        return messages.getFirst();
    }

    private Message message(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime sentAt = rs.getObject("sent_at", OffsetDateTime.class);
        return new Message(
                rs.getObject("id", UUID.class),
                rs.getString("body"),
                sentAt.toInstant(),
                rs.getString(4),
                rs.getString(5),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getObject("size_bytes", Long.class));
    }

    private void member(UUID conversationId, User user) {
        Long count = jdbc.queryForObject("""
                select count(*) from private_conversations
                where id=? and (participant_one=? or participant_two=?)
                """, Long.class, conversationId, user.getId(), user.getId());
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found");
        }
    }

    private Map<String, Object> find(String identifier) {
        List<Map<String, Object>> users = jdbc.queryForList("""
                select id, coalesce(student_id, login_id) as chat_login_id from users
                where status='ACTIVE' and (lower(student_id)=lower(?) or lower(login_id)=lower(?))
                """, identifier, identifier);
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }
        return users.getFirst();
    }

    private MediaType contentType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private MediaType photoContentType(String storageKey) {
        String extension = storageKey.toLowerCase(Locale.ROOT);
        if (extension.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return contentType(URLConnection.guessContentTypeFromName(storageKey));
    }

    public record SendRequest(@NotBlank @Size(max = 2000) String body) {}

    public record ChatUser(UUID id, String loginId, String nickname, String department, String section,
                           Integer academicYear, boolean hasProfilePhoto, Instant avatarVersion, long unreadCount,
                           String lastMessage, Instant lastMessageAt) {}

    public record Conversation(UUID id, String otherLoginId, String otherNickname, Instant lastMessageAt) {}

    public record Message(UUID id, String body, Instant sentAt, String senderLoginId, String senderNickname,
                          String attachmentName, String attachmentContentType, Long attachmentSizeBytes) {}

    private record StoredAttachment(String storageKey, String originalName, String contentType) {}
}
