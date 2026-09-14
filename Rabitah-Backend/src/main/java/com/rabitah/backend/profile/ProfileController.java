package com.rabitah.backend.profile;

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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Profile details and safe, authenticated avatar access for campus members. */
@RestController
@RequestMapping("/api/v1/profile")
public final class ProfileController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    private final MediaStorage storage;
    private final ApprovalEvents events;

    public ProfileController(JdbcTemplate jdbc, CurrentUserService current, MediaStorage storage, ApprovalEvents events) {
        this.jdbc = jdbc;
        this.current = current;
        this.storage = storage;
        this.events = events;
    }

    @PutMapping
    public ProfileView update(@Valid @RequestBody UpdateProfile request, Authentication auth) {
        User user = current.require(auth);
        String nickname = request.nickname().trim();
        if (nickname.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NICKNAME", "Enter a display name.");
        }
        jdbc.update("update users set nickname=?, updated_at=now() where id=?", nickname, user.getId());
        events.chatChanged();
        return profile(user.getId());
    }

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileView photo(@RequestPart MultipartFile file, Authentication auth) throws IOException {
        User user = current.require(auth);
        String contentType = file.getContentType();
        if (file.isEmpty() || contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "Please select a PNG, JPG, WEBP, or other image file.");
        }
        if (file.getSize() > 5_000_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PHOTO_TOO_LARGE", "Profile photos must be 5 MB or smaller.");
        }
        String key = storage.save("profiles/" + user.getId(), file);
        jdbc.update("update users set profile_photo_key=?, updated_at=now() where id=?", key, user.getId());
        events.chatChanged();
        return profile(user.getId());
    }

    @GetMapping("/photo")
    public ResponseEntity<byte[]> photo(Authentication auth) throws IOException {
        return photoFor(current.require(auth).getId());
    }

    /** Used by social, community, and private-chat avatar cells. */
    @GetMapping("/users/{id}/photo")
    public ResponseEntity<byte[]> userPhoto(@PathVariable UUID id, Authentication auth) throws IOException {
        current.require(auth);
        return photoFor(id);
    }

    private ResponseEntity<byte[]> photoFor(UUID userId) throws IOException {
        List<String> keys = jdbc.query(
                "select profile_photo_key from users where id=? and status='ACTIVE' and profile_photo_key is not null",
                (rs, rowNum) -> rs.getString(1), userId);
        if (keys.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "No profile photo yet.");
        }
        String key = keys.getFirst();
        return ResponseEntity.ok()
                .contentType(contentType(URLConnection.guessContentTypeFromName(key)))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
                .body(storage.read(key));
    }

    private ProfileView profile(UUID id) {
        return jdbc.queryForObject("""
                select id, login_id, student_id, nickname, role, department_code, section_code, academic_year,
                       (profile_photo_key is not null) as has_profile_photo, updated_at
                from users where id=?
                """, (rs, rowNum) -> new ProfileView(
                rs.getObject("id", UUID.class),
                rs.getString("login_id"),
                rs.getString("student_id"),
                rs.getString("nickname"),
                rs.getString("role"),
                rs.getString("department_code"),
                rs.getString("section_code"),
                rs.getObject("academic_year", Integer.class),
                rs.getBoolean("has_profile_photo"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()), id);
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

    public record UpdateProfile(@NotBlank @Size(max = 30) String nickname) {}

    public record ProfileView(UUID id, String loginId, String studentId, String nickname, String role,
                              String department, String section, Integer academicYear, boolean hasProfilePhoto,
                              Instant avatarVersion) {}
}
