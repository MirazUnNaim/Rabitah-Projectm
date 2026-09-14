package com.rabitah.backend.community;

import com.rabitah.backend.common.ApiException;
import com.rabitah.backend.realtime.ApprovalEvents;
import com.rabitah.backend.security.CurrentUserService;
import com.rabitah.backend.storage.MediaStorage;
import com.rabitah.backend.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
@RequestMapping("/api/v1/community")
public class CommunityController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    private final ApprovalEvents events;
    private final MediaStorage storage;

    public CommunityController(JdbcTemplate jdbc, CurrentUserService current, ApprovalEvents events, MediaStorage storage) {
        this.jdbc = jdbc;
        this.current = current;
        this.events = events;
        this.storage = storage;
    }

    @GetMapping("/rooms/mine")
    public Room mine(Authentication auth) {
        User user = current.require(auth);
        if (user.getDepartmentCode() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_COMMUNITY", "This account has no student community");
        }
        return jdbc.queryForObject("""
                select id, name, department_code, section_code, academic_year
                from community_rooms
                where department_code=? and section_code=? and academic_year=?
                """, (rs, rowNum) -> new Room(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)),
                user.getDepartmentCode(), user.getSectionCode(), user.getAcademicYear());
    }

    @GetMapping("/rooms/{id}/messages")
    public List<Message> messages(@PathVariable UUID id, Authentication auth) {
        User user = current.require(auth);
        access(id, user);
        return jdbc.query(messageSelect("where m.room_id=? order by m.created_at, m.id"),
                (rs, rowNum) -> message(rs), id);
    }

    @PostMapping(value = "/rooms/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Message publish(@PathVariable UUID id, @Valid @RequestBody MessageRequest request, Authentication auth) {
        return persist(id, request.body(), null, auth);
    }

    @PostMapping(value = "/rooms/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Message publishMedia(@PathVariable UUID id, @RequestParam(defaultValue = "") String body,
                                @RequestPart(value = "file", required = false) MultipartFile file,
                                Authentication auth) {
        if (file == null || file.isEmpty() || file.getSize() > 50_000_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Choose a file smaller than 50 MB.");
        }
        return persist(id, body, file, auth);
    }

    @GetMapping("/rooms/{roomId}/messages/{messageId}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID roomId, @PathVariable UUID messageId,
                                                      Authentication auth) throws IOException {
        User user = current.require(auth);
        access(roomId, user);
        List<StoredAttachment> attachments = jdbc.query("""
                select storage_key, original_name, content_type
                from community_messages
                where id=? and room_id=? and storage_key is not null
                """, (rs, rowNum) -> new StoredAttachment(rs.getString(1), rs.getString(2), rs.getString(3)),
                messageId, roomId);
        if (attachments.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COMMUNITY_ATTACHMENT_NOT_FOUND",
                    "This message has no attachment.");
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

    private Message persist(UUID roomId, String body, MultipartFile file, Authentication auth) {
        User user = current.require(auth);
        access(roomId, user);
        String text = body == null ? "" : body.trim();
        if (text.isBlank() && file == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "Write a message or attach a file.");
        }
        UUID messageId = UUID.randomUUID();
        String key = null;
        if (file != null) {
            try {
                key = storage.save("community/" + roomId, file);
            } catch (IOException error) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_SAVE_FAILED", "Could not save attachment.");
            }
        }
        jdbc.update("""
                insert into community_messages(id, room_id, author_id, body, storage_key, original_name, content_type, size_bytes)
                values(?,?,?,?,?,?,?,?)
                """, messageId, roomId, user.getId(), text, key,
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getContentType(),
                file == null ? null : file.getSize());
        events.chatChanged();
        return jdbc.queryForObject(messageSelect("where m.id=?"), (rs, rowNum) -> message(rs), messageId);
    }

    private String messageSelect(String ending) {
        return """
                select m.id, m.body, m.created_at,
                       u.id as author_id, u.nickname, coalesce(u.student_id, u.login_id) as author_login_id,
                       (u.profile_photo_key is not null) as author_has_profile_photo,
                       u.updated_at as author_avatar_version,
                       m.original_name, m.content_type, m.size_bytes
                from community_messages m
                join users u on u.id=m.author_id
                """ + ending;
    }

    private Message message(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        OffsetDateTime avatarVersion = rs.getObject("author_avatar_version", OffsetDateTime.class);
        return new Message(
                rs.getObject("id", UUID.class),
                rs.getString("body"),
                createdAt.toInstant(),
                rs.getObject("author_id", UUID.class),
                rs.getString("nickname"),
                rs.getString("author_login_id"),
                rs.getBoolean("author_has_profile_photo"),
                avatarVersion == null ? null : avatarVersion.toInstant(),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getObject("size_bytes", Long.class));
    }

    private void access(UUID id, User user) {
        Long count = jdbc.queryForObject("""
                select count(*) from community_rooms
                where id=? and (?='SYSTEM_ADMIN' or department_code=? and section_code=? and academic_year=?)
                """, Long.class, id, user.getRole().name(), user.getDepartmentCode(), user.getSectionCode(),
                user.getAcademicYear());
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "COMMUNITY_FORBIDDEN", "This room is outside your community");
        }
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

    public record MessageRequest(@NotBlank @Size(max = 3000) String body) {}

    public record Room(UUID id, String name, String department, String section, int academicYear) {}

    public record Message(UUID id, String body, Instant createdAt, UUID authorId, String authorNickname,
                          String authorLoginId, boolean authorHasProfilePhoto, Instant authorAvatarVersion,
                          String attachmentName, String attachmentContentType, Long attachmentSizeBytes) {}

    private record StoredAttachment(String storageKey, String originalName, String contentType) {}
}
