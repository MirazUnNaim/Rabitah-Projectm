package com.rabitah.backend.branding;

import com.rabitah.backend.common.ApiException;
import com.rabitah.backend.security.CurrentUserService;
import com.rabitah.backend.storage.MediaStorage;
import com.rabitah.backend.user.Role;
import com.rabitah.backend.user.User;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The public sign-in image is readable before authentication, but only SysAdmin can replace it. */
@RestController
@RequestMapping("/api/v1")
public class LoginBrandingController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUsers;
    private final MediaStorage storage;

    public LoginBrandingController(JdbcTemplate jdbc, CurrentUserService currentUsers, MediaStorage storage) {
        this.jdbc = jdbc;
        this.currentUsers = currentUsers;
        this.storage = storage;
    }

    @GetMapping("/auth/login-visual")
    public ResponseEntity<byte[]> loginVisual() throws IOException {
        List<StoredVisual> visuals = jdbc.query("select storage_key,content_type from login_branding where id=1",
                (rs, row) -> new StoredVisual(rs.getString(1), rs.getString(2)));
        if (visuals.isEmpty()) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        StoredVisual visual = visuals.getFirst();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(contentType(visual.contentType())).body(storage.read(visual.storageKey()));
    }

    @PostMapping(value = "/admin/login-visual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> updateLoginVisual(@RequestPart MultipartFile file, Authentication auth) throws IOException {
        User admin = requireAdmin(auth);
        if (file.isEmpty() || file.getSize() > 10_000_000 || !isImage(file)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOGIN_VISUAL",
                    "Choose an image file no larger than 10 MB.");
        }
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        String originalName = safeFilename(file.getOriginalFilename());
        String key = storage.save("branding/login", file);
        jdbc.update("""
                insert into login_branding(id,storage_key,original_name,content_type,updated_by,updated_at)
                values(1,?,?,?,?,now())
                on conflict(id) do update set storage_key=excluded.storage_key,original_name=excluded.original_name,
                    content_type=excluded.content_type,updated_by=excluded.updated_by,updated_at=now()
                """, key, originalName, contentType, admin.getId());
        return Map.of("message", "Login image updated", "filename", originalName);
    }

    private User requireAdmin(Authentication auth) {
        User user = currentUsers.require(auth);
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "System administrator access is required");
        }
        return user;
    }

    private boolean isImage(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        return type.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$");
    }

    private String safeFilename(String filename) {
        return filename == null || filename.isBlank() ? "login-visual" : filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    }

    private MediaType contentType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try { return MediaType.parseMediaType(value); }
        catch (IllegalArgumentException ignored) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    private record StoredVisual(String storageKey, String contentType) {}
}
