package com.rabitah.backend.notice;

import com.rabitah.backend.common.ApiException;
import com.rabitah.backend.realtime.ApprovalEvents;
import com.rabitah.backend.security.CurrentUserService;
import com.rabitah.backend.storage.MediaStorage;
import com.rabitah.backend.user.Role;
import com.rabitah.backend.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/notices")
public class NoticeController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    private final MediaStorage storage;
    private final NoticePdfService pdfs;
    private final ApprovalEvents events;

    public NoticeController(JdbcTemplate jdbc, CurrentUserService current, MediaStorage storage, NoticePdfService pdfs,
                            ApprovalEvents events) {
        this.jdbc = jdbc;
        this.current = current;
        this.storage = storage;
        this.pdfs = pdfs;
        this.events = events;
    }

    /** Headline data only; opening an item retrieves its detail and records a read receipt. */
    @GetMapping
    public List<NoticeSummary> list(@RequestParam(defaultValue = "") String q, Authentication auth) {
        User user = current.require(auth);
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                select n.id, n.notice_type, n.title, n.department_code, n.academic_year, n.section_code, n.published_at,
                       not exists(select 1 from notice_reads nr where nr.notice_id=n.id and nr.user_id=?) as unread
                from notices n
                where n.deleted_at is null and (n.expires_at is null or n.expires_at>now())
                  and (lower(n.title) like ? or lower(n.body) like ?)
                """ + visibleWhere() + " order by n.published_at desc", (rs, rowNum) -> new NoticeSummary(
                rs.getObject("id", UUID.class), rs.getString("notice_type"), rs.getString("title"),
                rs.getString("department_code"), rs.getObject("academic_year", Integer.class),
                rs.getString("section_code"), rs.getObject("published_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getBoolean("unread"), user.getRole() == Role.SYSTEM_ADMIN),
                arguments(user, user.getId(), like, like));
    }

    @GetMapping("/{id}")
    @Transactional
    public NoticeDetail detail(@PathVariable UUID id, Authentication auth) {
        User user = current.require(auth);
        NoticeDetail detail = findVisible(id, user);
        jdbc.update("""
                insert into notice_reads(notice_id,user_id,read_at) values(?,?,now())
                on conflict(notice_id,user_id) do update set read_at=excluded.read_at
                """, id, user.getId());
        return detail.read();
    }

    @GetMapping("/{id}/pdf")
    @Transactional
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) throws IOException {
        NoticeDetail notice = detail(id, auth);
        String filename = safeFilename(notice.filename() == null ? "notice-" + id + ".pdf" : notice.filename());
        byte[] bytes;
        if (notice.storageKey() == null || notice.storageKey().isBlank()) {
            bytes = pdfs.create(notice.title(), notice.body(),
                    scopeLabel(notice.department(), notice.academicYear(), notice.section()));
            String key = storage.saveBytes("notices/" + id, filename, bytes, MediaType.APPLICATION_PDF_VALUE);
            jdbc.update("""
                    update notices set storage_key=?, original_name=?, content_type=?, size_bytes=?, updated_at=now()
                    where id=?
                    """, key, filename, MediaType.APPLICATION_PDF_VALUE, bytes.length, id);
        } else {
            bytes = storage.read(notice.storageKey());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public NoticeDetail create(@Valid @RequestBody NoticeRequest request, Authentication auth) throws IOException {
        return publish(request.title(), request.body(), request.type(), request.department(), request.academicYear(),
                request.section(), null, auth);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public NoticeDetail createPdf(@RequestParam String title, @RequestParam(defaultValue = "") String body,
                                  @RequestParam(defaultValue = "GENERAL") String type,
                                  @RequestParam(required = false) String department,
                                  @RequestParam(required = false) Integer academicYear,
                                  @RequestParam(required = false) String section,
                                  @RequestPart(value = "file", required = false) MultipartFile file,
                                  Authentication auth) throws IOException {
        return publish(title, body, type, department, academicYear, section, file, auth);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id, Authentication auth) {
        User user = current.require(auth);
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOTICE_DELETE_FORBIDDEN", "Only SysAdmin can delete notices.");
        }
        int changed = jdbc.update("update notices set deleted_at=now(),updated_at=now() where id=? and deleted_at is null", id);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "Notice not found.");
        }
        events.chatChanged();
    }

    private NoticeDetail publish(String rawTitle, String rawBody, String type, String requestedDepartment,
                                 Integer academicYear, String section, MultipartFile file, Authentication auth)
            throws IOException {
        User user = current.require(auth);
        if (user.getRole() != Role.SYSTEM_ADMIN && user.getRole() != Role.DEPARTMENT_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only administrators may publish notices.");
        }
        String title = required(rawTitle, "Notice title is required.", 200);
        String body = rawBody == null ? "" : rawBody.trim();
        String noticeType = validType(type);
        String department = user.getRole() == Role.DEPARTMENT_ADMIN ? user.getDepartmentCode() : blank(requestedDepartment);
        String sectionCode = blank(section);
        if (sectionCode != null && !sectionCode.matches("[AB]")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SECTION", "Section must be A or B.");
        }
        if (academicYear != null && (academicYear < 1 || academicYear > 4)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACADEMIC_YEAR", "Academic year must be between 1 and 4.");
        }
        if (file == null && body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_NOTICE", "Write notice details or attach a PDF.");
        }
        UUID id = UUID.randomUUID();
        String key;
        String filename;
        long sizeBytes;
        if (file != null && !file.isEmpty()) {
            validatePdf(file);
            key = storage.save("notices/" + id, file);
            filename = file.getOriginalFilename();
            sizeBytes = file.getSize();
        } else {
            filename = "notice-" + id + ".pdf";
            String scope = scopeLabel(department, academicYear, sectionCode);
            byte[] generated = pdfs.create(title, body, scope);
            key = storage.saveBytes("notices/" + id, filename, generated, MediaType.APPLICATION_PDF_VALUE);
            sizeBytes = generated.length;
        }
        jdbc.update("""
                insert into notices(id,department_code,title,body,storage_key,original_name,content_type,size_bytes,
                                    published_by,notice_type,academic_year,section_code,published_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
                """, id, department, title, body, key, filename, MediaType.APPLICATION_PDF_VALUE,
                sizeBytes, user.getId(), noticeType, academicYear, sectionCode);
        events.chatChanged();
        return findVisible(id, user).read();
    }

    private NoticeDetail findVisible(UUID id, User user) {
        List<NoticeDetail> rows = jdbc.query("""
                select n.id,n.notice_type,n.title,n.body,n.department_code,n.academic_year,n.section_code,n.published_at,
                       n.storage_key,n.original_name,
                       not exists(select 1 from notice_reads nr where nr.notice_id=n.id and nr.user_id=?) as unread
                from notices n
                where n.id=? and n.deleted_at is null and (n.expires_at is null or n.expires_at>now())
                """ + visibleWhere(), (rs, rowNum) -> new NoticeDetail(
                rs.getObject("id", UUID.class), rs.getString("notice_type"), rs.getString("title"),
                rs.getString("body"), rs.getString("department_code"), rs.getObject("academic_year", Integer.class),
                rs.getString("section_code"), rs.getObject("published_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getString("storage_key"), rs.getString("original_name"), rs.getBoolean("unread"),
                user.getRole() == Role.SYSTEM_ADMIN), arguments(user, user.getId(), id));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "Notice is unavailable to this account.");
        }
        return rows.getFirst();
    }

    private String visibleWhere() {
        return """
                  and (?='SYSTEM_ADMIN'
                    or (?='DEPARTMENT_ADMIN' and (n.department_code is null or n.department_code=?))
                    or ((n.department_code is null or n.department_code=?)
                      and (n.academic_year is null or n.academic_year=?)
                      and (n.section_code is null or n.section_code=?)))
                """;
    }

    private Object[] visibilityArguments(User user) {
        return new Object[]{user.getRole().name(), user.getRole().name(), user.getDepartmentCode(),
                user.getDepartmentCode(), user.getAcademicYear(), user.getSectionCode()};
    }

    private Object[] arguments(User user, Object... prefix) {
        Object[] visibility = visibilityArguments(user);
        Object[] arguments = java.util.Arrays.copyOf(prefix, prefix.length + visibility.length);
        System.arraycopy(visibility, 0, arguments, prefix.length, visibility.length);
        return arguments;
    }

    private String required(String value, String message, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || text.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NOTICE", message);
        }
        return text;
    }

    private String validType(String value) {
        String type = value == null ? "GENERAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!type.matches("GENERAL|ACADEMIC|EXAM|EVENT|EMERGENCY")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NOTICE_TYPE", "Choose a valid notice type.");
        }
        return type;
    }

    private void validatePdf(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (file.isEmpty() || file.getSize() > 20_000_000 || !filename.endsWith(".pdf")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NOTICE_PDF", "Attach a PDF up to 20 MB.");
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String scopeLabel(String department, Integer year, String section) {
        List<String> labels = new java.util.ArrayList<>();
        if (department != null) labels.add(department);
        if (year != null) labels.add("Year " + year);
        if (section != null) labels.add("Section " + section);
        return String.join(" · ", labels);
    }

    private String safeFilename(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    }

    public record NoticeRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 4000) String body,
                                @Pattern(regexp = "GENERAL|ACADEMIC|EXAM|EVENT|EMERGENCY") String type,
                                String department, Integer academicYear, String section) {}

    public record NoticeSummary(UUID id, String type, String title, String department, Integer academicYear,
                                String section, Instant publishedAt, boolean unread, boolean canDelete) {}

    public record NoticeDetail(UUID id, String type, String title, String body, String department,
                               Integer academicYear, String section, Instant publishedAt, String storageKey,
                               String filename, boolean unread, boolean canDelete) {
        NoticeDetail read() {
            return new NoticeDetail(id, type, title, body, department, academicYear, section, publishedAt,
                    storageKey, filename, false, canDelete);
        }
    }
}
