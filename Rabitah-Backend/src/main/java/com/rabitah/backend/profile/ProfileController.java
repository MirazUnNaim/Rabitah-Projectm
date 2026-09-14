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
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /** A campus member's read-only identity card, used by avatar profile viewers. */
    @GetMapping("/users/{id}")
    public ProfileView userProfile(@PathVariable UUID id, Authentication auth) {
        current.require(auth);
        return activeProfile(id);
    }

    /** Profile-gallery images are visible to signed-in campus members, never anonymously. */
    @GetMapping("/users/{id}/gallery")
    public List<GalleryMediaView> userGallery(@PathVariable UUID id, Authentication auth) {
        current.require(auth);
        activeProfile(id);
        return galleryFor(id);
    }

    @GetMapping("/users/{userId}/gallery/{id}")
    public ResponseEntity<byte[]> userGalleryMedia(@PathVariable UUID userId, @PathVariable UUID id,
                                                    Authentication auth) throws IOException {
        current.require(auth);
        activeProfile(userId);
        List<StoredGalleryMedia> items = jdbc.query("select storage_key,content_type from profile_gallery_media where id=? and user_id=?",
                (rs, row) -> new StoredGalleryMedia(rs.getString(1), rs.getString(2)), id, userId);
        if (items.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "GALLERY_MEDIA_NOT_FOUND", "Profile media was not found.");
        StoredGalleryMedia item = items.getFirst();
        return ResponseEntity.ok().contentType(contentType(item.contentType())).cacheControl(CacheControl.noStore()).body(storage.read(item.storageKey()));
    }

    @GetMapping("/gallery")
    public List<GalleryMediaView> gallery(Authentication auth) {
        User user = current.require(auth);
        return galleryFor(user.getId());
    }

    @PostMapping(value = "/gallery", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GalleryMediaView addGalleryMedia(@RequestPart MultipartFile file, Authentication auth) throws IOException {
        User user = current.require(auth);
        if (file.isEmpty() || !isGalleryImage(file)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GALLERY_MEDIA", "Choose a photo or GIF image file.");
        }
        if (file.getSize() > 10_000_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GALLERY_MEDIA_TOO_LARGE", "Each profile photo or GIF must be 10 MB or smaller.");
        }
        Integer currentCount = jdbc.queryForObject("select count(*) from profile_gallery_media where user_id=?", Integer.class, user.getId());
        if (currentCount != null && currentCount >= 24) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GALLERY_FULL", "Your profile gallery can contain up to 24 photos or GIFs.");
        }
        UUID id = UUID.randomUUID();
        String filename = safeFilename(file.getOriginalFilename());
        String contentType = galleryContentType(file);
        String key = storage.save("profiles/" + user.getId() + "/gallery", file);
        jdbc.update("insert into profile_gallery_media(id,user_id,storage_key,original_name,content_type,size_bytes) values(?,?,?,?,?,?)",
                id, user.getId(), key, filename, contentType, file.getSize());
        return new GalleryMediaView(id, filename, contentType, file.getSize(), Instant.now());
    }

    @GetMapping("/gallery/{id}")
    public ResponseEntity<byte[]> galleryMedia(@PathVariable UUID id, Authentication auth) throws IOException {
        User user = current.require(auth);
        List<StoredGalleryMedia> items = jdbc.query("select storage_key,content_type from profile_gallery_media where id=? and user_id=?",
                (rs, row) -> new StoredGalleryMedia(rs.getString(1), rs.getString(2)), id, user.getId());
        if (items.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "GALLERY_MEDIA_NOT_FOUND", "Profile media was not found.");
        StoredGalleryMedia item = items.getFirst();
        return ResponseEntity.ok().contentType(contentType(item.contentType())).cacheControl(CacheControl.noStore()).body(storage.read(item.storageKey()));
    }

    @DeleteMapping("/gallery/{id}")
    public void deleteGalleryMedia(@PathVariable UUID id, Authentication auth) throws IOException {
        User user = current.require(auth);
        List<StoredGalleryMedia> items = jdbc.query("select storage_key,content_type from profile_gallery_media where id=? and user_id=?",
                (rs, row) -> new StoredGalleryMedia(rs.getString(1), rs.getString(2)), id, user.getId());
        if (items.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "GALLERY_MEDIA_NOT_FOUND", "Profile media was not found.");
        storage.delete(items.getFirst().storageKey());
        jdbc.update("delete from profile_gallery_media where id=? and user_id=?", id, user.getId());
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

    private ProfileView activeProfile(UUID id) {
        List<ProfileView> profiles = jdbc.query("""
                select id, login_id, student_id, nickname, role, department_code, section_code, academic_year,
                       (profile_photo_key is not null) as has_profile_photo, updated_at
                from users where id=? and status='ACTIVE'
                """, (rs, rowNum) -> new ProfileView(
                rs.getObject("id", UUID.class), rs.getString("login_id"), rs.getString("student_id"),
                rs.getString("nickname"), rs.getString("role"), rs.getString("department_code"),
                rs.getString("section_code"), rs.getObject("academic_year", Integer.class),
                rs.getBoolean("has_profile_photo"), rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()), id);
        if (profiles.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "This campus profile is unavailable.");
        }
        return profiles.getFirst();
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

    private List<GalleryMediaView> galleryFor(UUID userId) {
        return jdbc.query("select id,original_name,content_type,size_bytes,created_at from profile_gallery_media where user_id=? order by created_at desc",
                (rs, row) -> new GalleryMediaView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getObject(5, java.time.OffsetDateTime.class).toInstant()), userId);
    }

    private boolean isGalleryImage(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || filename.matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$");
    }

    private String galleryContentType(MultipartFile file) {
        String value = file.getContentType();
        return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : value;
    }

    private String safeFilename(String filename) {
        return filename == null || filename.isBlank() ? "profile-media" : filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    }

    public record UpdateProfile(@NotBlank @Size(max = 30) String nickname) {}

    public record ProfileView(UUID id, String loginId, String studentId, String nickname, String role,
                              String department, String section, Integer academicYear, boolean hasProfilePhoto,
                              Instant avatarVersion) {}
    public record GalleryMediaView(UUID id, String originalName, String contentType, long sizeBytes, Instant createdAt) {}
    private record StoredGalleryMedia(String storageKey, String contentType) {}
}
