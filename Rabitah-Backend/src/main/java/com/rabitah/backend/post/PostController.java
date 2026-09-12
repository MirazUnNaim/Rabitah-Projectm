package com.rabitah.backend.post;

import com.rabitah.backend.common.ApiException;
import com.rabitah.backend.security.CurrentUserService;
import com.rabitah.backend.realtime.ApprovalEvents;
import com.rabitah.backend.user.Role;
import com.rabitah.backend.user.User;
import com.rabitah.backend.storage.MediaStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class PostController {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUsers;
    private final ApprovalEvents events;
    private final MediaStorage storage;

    public PostController(JdbcTemplate jdbc, CurrentUserService currentUsers, ApprovalEvents events, MediaStorage storage) { this.jdbc = jdbc; this.currentUsers = currentUsers; this.events = events; this.storage=storage; }

    @GetMapping("/posts/feed")
    public List<PostView> feed(Authentication auth) {
        User user = currentUsers.require(auth);
        return jdbc.query("""
                select p.id,p.body,p.status,p.created_at,u.nickname,u.student_id,
                  count(distinct case when r.reaction='LIKE' then r.user_id end) likes,
                  count(distinct case when r.reaction='DISLIKE' then r.user_id end) dislikes,
                  count(distinct c.id) comments,
                  max(case when r.user_id=? then r.reaction end) my_reaction
                from posts p join users u on u.id=p.author_id
                left join post_reactions r on r.post_id=p.id
                left join comments c on c.post_id=p.id and c.deleted_at is null
                where p.status='APPROVED' and p.deleted_at is null
                  and (?='SYSTEM_ADMIN' or (p.department_code is null or p.department_code=?)
                    and (p.section_code is null or p.section_code=?)
                    and (p.academic_year is null or p.academic_year=?))
                group by p.id,u.nickname,u.student_id order by p.created_at desc limit 100
                """, this::post, user.getId(), user.getRole().name(), user.getDepartmentCode(), user.getSectionCode(), user.getAcademicYear());
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PostView create(@Valid @RequestBody CreatePost request, Authentication auth) {
        User user = currentUsers.require(auth);
        UUID id = UUID.randomUUID();
        String status = user.getRole() == Role.SYSTEM_ADMIN ? "APPROVED" : "PENDING";
        jdbc.update("insert into posts(id,author_id,body,visibility,status,department_code,section_code,academic_year,created_at,updated_at) values(?,?,?,?,?,?,?,?,now(),now())",
                id,user.getId(),request.body().trim(),"SCOPED",status,empty(request.department()),empty(request.section()),request.academicYear());
        if ("PENDING".equals(status)) events.approvalsChanged();
        return get(id, auth);
    }

    @PostMapping(value="/posts", consumes=org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PostView createWithMedia(@RequestParam(defaultValue="") String body,@RequestParam(required=false) String department,@RequestParam(required=false) String section,@RequestParam(required=false) Integer academicYear,@RequestPart MultipartFile file,Authentication auth) throws java.io.IOException {
        User user=currentUsers.require(auth);
        if(file.isEmpty()||file.getContentType()==null||!(file.getContentType().startsWith("image/")||file.getContentType().startsWith("video/")))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_MEDIA","Attach an image or video file.");
        if(file.getSize()>50_000_000)throw new ApiException(HttpStatus.BAD_REQUEST,"MEDIA_TOO_LARGE","Media must be 50 MB or smaller.");
        UUID id=UUID.randomUUID();String status=user.getRole()==Role.SYSTEM_ADMIN?"APPROVED":"PENDING";String key=storage.save("posts/"+id,file);
        jdbc.update("insert into posts(id,author_id,body,visibility,status,department_code,section_code,academic_year,created_at,updated_at) values(?,?,?,?,?,?,?,?,now(),now())",id,user.getId(),body.trim(),"SCOPED",status,empty(department),empty(section),academicYear);
        jdbc.update("insert into post_media(post_id,storage_key,original_name,content_type,size_bytes) values(?,?,?,?,?)",id,key,file.getOriginalFilename(),file.getContentType(),file.getSize());
        if("PENDING".equals(status))events.approvalsChanged();return get(id,auth);
    }

    @GetMapping("/posts/{id}")
    public PostView get(@PathVariable UUID id, Authentication auth) {
        User user=currentUsers.require(auth);
        List<PostView> rows=jdbc.query("""
                select p.id,p.body,p.status,p.created_at,u.nickname,u.student_id,
                count(distinct case when r.reaction='LIKE' then r.user_id end) likes,
                count(distinct case when r.reaction='DISLIKE' then r.user_id end) dislikes,
                count(distinct c.id) comments,max(case when r.user_id=? then r.reaction end) my_reaction
                from posts p join users u on u.id=p.author_id left join post_reactions r on r.post_id=p.id
                left join comments c on c.post_id=p.id and c.deleted_at is null where p.id=? and p.deleted_at is null
                group by p.id,u.nickname,u.student_id""",this::post,user.getId(),id);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"POST_NOT_FOUND","Post not found");return rows.getFirst();
    }

    @PutMapping("/posts/{id}/reaction")
    @Transactional
    public PostView react(@PathVariable UUID id,@Valid @RequestBody Reaction request,Authentication auth){User u=currentUsers.require(auth);get(id,auth);jdbc.update("insert into post_reactions(post_id,user_id,reaction) values(?,?,?) on conflict(post_id,user_id) do update set reaction=excluded.reaction,created_at=now()",id,u.getId(),request.type());return get(id,auth);}

    @DeleteMapping("/posts/{id}/reaction")
    @Transactional public PostView removeReaction(@PathVariable UUID id,Authentication auth){User u=currentUsers.require(auth);jdbc.update("delete from post_reactions where post_id=? and user_id=?",id,u.getId());return get(id,auth);}

    @GetMapping("/posts/{id}/comments")
    public List<CommentView> comments(@PathVariable UUID id,Authentication auth){User user=currentUsers.require(auth);get(id,auth);return jdbc.query("""
            select c.id,c.body,c.created_at,u.nickname,u.student_id,c.parent_comment_id,
              count(distinct case when r.reaction='LIKE' then r.user_id end) likes,
              count(distinct case when r.reaction='DISLIKE' then r.user_id end) dislikes,
              max(case when r.user_id=? then r.reaction end) my_reaction
            from comments c join users u on u.id=c.author_id
            left join comment_reactions r on r.comment_id=c.id
            where c.post_id=? and c.deleted_at is null
            group by c.id,u.nickname,u.student_id order by c.created_at
            """,(rs,n)->commentView(rs),user.getId(),id);}

    @PostMapping("/posts/{id}/comments") @ResponseStatus(HttpStatus.CREATED)
    @Transactional public CommentView comment(@PathVariable UUID id,@Valid @RequestBody CommentRequest request,Authentication auth){User u=currentUsers.require(auth);get(id,auth);if(request.parentCommentId()!=null){Integer count=jdbc.queryForObject("select count(*) from comments where id=? and post_id=? and deleted_at is null",Integer.class,request.parentCommentId(),id);if(count==null||count==0)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PARENT_COMMENT","The reply target is not a comment on this post");}UUID cid=UUID.randomUUID();jdbc.update("insert into comments(id,post_id,author_id,body,parent_comment_id,created_at,updated_at) values(?,?,?,?,?,now(),now())",cid,id,u.getId(),request.body().trim(),request.parentCommentId());return comments(id,auth).stream().filter(c->c.id().equals(cid)).findFirst().orElseThrow();}

    @PutMapping("/comments/{id}/reaction")
    @Transactional public CommentView reactToComment(@PathVariable UUID id,@Valid @RequestBody Reaction request,Authentication auth){User u=currentUsers.require(auth);UUID postId=commentPostId(id);get(postId,auth);jdbc.update("insert into comment_reactions(comment_id,user_id,reaction) values(?,?,?) on conflict(comment_id,user_id) do update set reaction=excluded.reaction,created_at=now()",id,u.getId(),request.type());return comments(postId,auth).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}

    @DeleteMapping("/comments/{id}/reaction")
    @Transactional public CommentView removeCommentReaction(@PathVariable UUID id,Authentication auth){User u=currentUsers.require(auth);UUID postId=commentPostId(id);get(postId,auth);jdbc.update("delete from comment_reactions where comment_id=? and user_id=?",id,u.getId());return comments(postId,auth).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}

    private PostView post(ResultSet rs,int row)throws SQLException{return new PostView(rs.getObject("id",UUID.class),rs.getString("body"),rs.getString("status"),rs.getObject("created_at",java.time.OffsetDateTime.class).toInstant(),rs.getString("nickname"),rs.getString("student_id"),rs.getLong("likes"),rs.getLong("dislikes"),rs.getLong("comments"),rs.getString("my_reaction"));}
    private CommentView commentView(ResultSet rs)throws SQLException{return new CommentView(rs.getObject("id",UUID.class),rs.getString("body"),rs.getObject("created_at",java.time.OffsetDateTime.class).toInstant(),rs.getString("nickname"),rs.getString("student_id"),rs.getObject("parent_comment_id",UUID.class),rs.getLong("likes"),rs.getLong("dislikes"),rs.getString("my_reaction"));}
    private UUID commentPostId(UUID id){List<UUID> rows=jdbc.query("select post_id from comments where id=? and deleted_at is null",(rs,n)->rs.getObject(1,UUID.class),id);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"COMMENT_NOT_FOUND","Comment not found");return rows.getFirst();}
    private String empty(String s){return s==null||s.isBlank()?null:s;}
    public record CreatePost(@NotBlank @Size(max=2000) String body,String department,String section,Integer academicYear){}
    public record Reaction(@Pattern(regexp="LIKE|DISLIKE") String type){}
    public record CommentRequest(@NotBlank @Size(max=1000) String body,UUID parentCommentId){}
    public record PostView(UUID id,String body,String status,Instant createdAt,String authorNickname,String authorStudentId,long likes,long dislikes,long comments,String myReaction){}
    public record CommentView(UUID id,String body,Instant createdAt,String authorNickname,String authorStudentId,UUID parentCommentId,long likes,long dislikes,String myReaction){}
}
