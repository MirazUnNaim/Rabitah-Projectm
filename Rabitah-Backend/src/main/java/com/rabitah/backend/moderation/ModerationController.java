package com.rabitah.backend.moderation;

import com.rabitah.backend.common.ApiException;
import com.rabitah.backend.security.CurrentUserService;
import com.rabitah.backend.realtime.ApprovalEvents;
import com.rabitah.backend.user.Role;
import com.rabitah.backend.user.User;
import com.rabitah.backend.user.UserRepository;
import com.rabitah.backend.storage.MediaStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/approvals")
public class ModerationController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUsers;
    private final UserRepository users;
    private final ApprovalEvents events;
    private final MediaStorage storage;
    private final PasswordEncoder passwords;

    public ModerationController(JdbcTemplate jdbc, CurrentUserService currentUsers, UserRepository users, ApprovalEvents events, MediaStorage storage, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.currentUsers = currentUsers;
        this.users = users;
        this.events = events;
        this.storage = storage;
        this.passwords = passwords;
    }

    @GetMapping("/summary")
    public ApprovalSummary summary(Authentication auth) {
        requireAdmin(auth);
        return new ApprovalSummary(count("select count(*) from users where status='PENDING'"),
                count("select count(*) from posts where status='PENDING' and deleted_at is null"),
                count("select count(*) from question_papers where status='PENDING'"),
                count("select count(*) from password_reset_requests where status='PENDING'"));
    }

    @GetMapping("/users")
    public List<UserRequest> pendingUsers(Authentication auth) {
        requireAdmin(auth);
        return jdbc.query("select id,student_id,legal_name,nickname,department_code,section_code,academic_year,created_at from users where status='PENDING' order by created_at",
                (rs,n)->new UserRequest(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,Integer.class),rs.getObject(8,java.time.OffsetDateTime.class).toInstant()));
    }

    @GetMapping("/posts")
    public List<PostRequest> pendingPosts(Authentication auth) {
        requireAdmin(auth);
        List<PostRequest> posts = jdbc.query("select p.id,p.body,p.created_at,u.nickname,u.student_id from posts p join users u on u.id=p.author_id where p.status='PENDING' and p.deleted_at is null order by p.created_at",
                (rs,n)->new PostRequest(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,java.time.OffsetDateTime.class).toInstant(),rs.getString(4),rs.getString(5),List.of()));
        return posts.stream().map(post -> new PostRequest(post.id(), post.body(), post.createdAt(), post.authorNickname(), post.authorStudentId(), mediaFor(post.id()))).toList();
    }

    /** Pending attachments are visible only to a system administrator reviewing their owning post. */
    @GetMapping("/posts/{postId}/media/{mediaId}")
    public ResponseEntity<byte[]> pendingPostMedia(@PathVariable UUID postId, @PathVariable UUID mediaId, Authentication auth) throws java.io.IOException {
        requireAdmin(auth);
        List<StoredMedia> media = jdbc.query("""
                select pm.storage_key,pm.content_type
                from post_media pm join posts p on p.id=pm.post_id
                where pm.id=? and pm.post_id=? and p.status='PENDING' and p.deleted_at is null
                """, (rs,n) -> new StoredMedia(rs.getString(1), rs.getString(2)), mediaId, postId);
        if (media.isEmpty()) throw notFound("POST_MEDIA_NOT_FOUND", "This pending attachment was not found");
        StoredMedia item = media.getFirst();
        return ResponseEntity.ok().contentType(contentType(item.contentType())).body(storage.read(item.storageKey()));
    }

    @GetMapping("/papers")
    public List<PaperRequest> pendingPapers(Authentication auth) {
        requireAdmin(auth);
        return jdbc.query("select qp.id,qp.title,qp.original_name,qp.exam_year,qp.created_at,c.course_code,u.nickname,u.student_id from question_papers qp join courses c on c.id=qp.course_id join users u on u.id=qp.uploaded_by where qp.status='PENDING' order by qp.created_at",
                (rs,n)->new PaperRequest(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getObject(5,java.time.OffsetDateTime.class).toInstant(),rs.getString(6),rs.getString(7),rs.getString(8)));
    }

    @GetMapping("/password-resets")
    public List<PasswordResetRequest> pendingPasswordResets(Authentication auth) {
        requireAdmin(auth);
        return jdbc.query("""
                select pr.id,u.login_id,u.student_id,u.nickname,pr.requested_at
                from password_reset_requests pr join users u on u.id=pr.user_id
                where pr.status='PENDING' order by pr.requested_at
                """, (rs,n) -> new PasswordResetRequest(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getObject(5, java.time.OffsetDateTime.class).toInstant()));
    }

    @PutMapping("/users/{id}")
    @Transactional
    public Map<String,String> decideUser(@PathVariable UUID id,@Valid @RequestBody Decision decision,Authentication auth) {
        requireAdmin(auth); User user=users.findById(id).orElseThrow(()->notFound("USER_NOT_FOUND","User request not found"));
        if(decision.action().equals("APPROVE"))user.approve();else user.reject(); users.saveAndFlush(user);events.approvalsChanged();return Map.of("message","User request "+decision.action().toLowerCase()+"d");
    }

    @PutMapping("/posts/{id}")
    @Transactional
    public Map<String,String> decidePost(@PathVariable UUID id,@Valid @RequestBody Decision decision,Authentication auth) {
        User admin=requireAdmin(auth);int changed=jdbc.update("update posts set status=?,moderated_by=?,moderated_at=now(),rejection_reason=?,updated_at=now() where id=? and status='PENDING'",decision.action().equals("APPROVE")?"APPROVED":"REJECTED",admin.getId(),decision.reason(),id);if(changed==0)throw notFound("POST_REQUEST_NOT_FOUND","Pending post not found");events.approvalsChanged();return Map.of("message","Post "+decision.action().toLowerCase()+"d");
    }

    @PutMapping("/papers/{id}")
    @Transactional
    public Map<String,String> decidePaper(@PathVariable UUID id,@Valid @RequestBody Decision decision,Authentication auth) {
        User admin=requireAdmin(auth);
        String replacementTitle=decision.title()==null?null:decision.title().trim();
        if("APPROVE".equals(decision.action())&&replacementTitle!=null&&replacementTitle.isBlank())throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TITLE","A repository title is required when approving a question PDF.");
        int changed=jdbc.update("update question_papers set status=?,moderated_by=?,moderated_at=now(),rejection_reason=?,title=case when ?='APPROVE' then coalesce(?,title) else title end where id=? and status='PENDING'",decision.action().equals("APPROVE")?"APPROVED":"REJECTED",admin.getId(),decision.reason(),decision.action(),replacementTitle,id);
        if(changed==0)throw notFound("PAPER_REQUEST_NOT_FOUND","Pending paper not found");
        events.approvalsChanged();
        return Map.of("message","Paper "+decision.action().toLowerCase()+"d");
    }

    @PutMapping("/password-resets/{id}")
    @Transactional
    public Map<String,String> decidePasswordReset(@PathVariable UUID id,@Valid @RequestBody Decision decision,Authentication auth) {
        User admin=requireAdmin(auth);
        List<StoredPasswordReset> requests=jdbc.query("select user_id,requested_password_hash from password_reset_requests where id=? and status='PENDING'",(rs,n)->new StoredPasswordReset(rs.getObject(1,UUID.class),rs.getString(2)),id);
        if(requests.isEmpty())throw notFound("PASSWORD_RESET_NOT_FOUND","Password-reset request not found");
        StoredPasswordReset reset=requests.getFirst();
        if("APPROVE".equals(decision.action())){
            User account=users.findById(reset.userId()).orElseThrow(()->notFound("USER_NOT_FOUND","User account not found"));
            account.changePassword(reset.passwordHash());
            users.saveAndFlush(account);
        }
        int changed=jdbc.update("update password_reset_requests set status=?,reviewed_by=?,reviewed_at=now(),rejection_reason=? where id=? and status='PENDING'", "APPROVE".equals(decision.action())?"APPROVED":"REJECTED",admin.getId(),decision.reason(),id);
        if(changed==0)throw notFound("PASSWORD_RESET_NOT_FOUND","Password-reset request not found");
        events.approvalsChanged();
        return Map.of("message","Password-reset request "+decision.action().toLowerCase()+"d");
    }

    private User requireAdmin(Authentication auth){User u=currentUsers.require(auth);if(u.getRole()!=Role.SYSTEM_ADMIN)throw new ApiException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","System administrator access is required");return u;}
    private List<MediaRequest> mediaFor(UUID postId){return jdbc.query("select id,original_name,content_type,size_bytes from post_media where post_id=? order by created_at",(rs,n)->new MediaRequest(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),postId);}
    private MediaType contentType(String value){if(value==null||value.isBlank())return MediaType.APPLICATION_OCTET_STREAM;try{return MediaType.parseMediaType(value);}catch(IllegalArgumentException ignored){return MediaType.APPLICATION_OCTET_STREAM;}}
    private long count(String sql){Long value=jdbc.queryForObject(sql,Long.class);return value==null?0:value;}
    private ApiException notFound(String code,String message){return new ApiException(HttpStatus.NOT_FOUND,code,message);}
    public record Decision(@Pattern(regexp="APPROVE|REJECT") String action,@Size(max=500) String reason,@Size(max=200) String title){}
    public record ApprovalSummary(long users,long posts,long papers,long passwordResets){}
    public record UserRequest(UUID id,String studentId,String legalName,String nickname,String department,String section,Integer academicYear,Instant requestedAt){}
    public record PostRequest(UUID id,String body,Instant createdAt,String authorNickname,String authorStudentId,List<MediaRequest> attachments){}
    public record MediaRequest(UUID id,String originalName,String contentType,long sizeBytes){}
    public record PaperRequest(UUID id,String title,String filename,int examYear,Instant createdAt,String courseCode,String authorNickname,String authorStudentId){}
    public record PasswordResetRequest(UUID id,String loginId,String studentId,String nickname,Instant requestedAt){}
    private record StoredMedia(String storageKey,String contentType){}
    private record StoredPasswordReset(UUID userId,String passwordHash){}
}
