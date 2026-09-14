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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
        return posts(user, """
                select p.id,p.body,p.status,p.created_at,p.author_id,u.nickname,u.student_id,
                  (u.profile_photo_key is not null) as author_has_profile_photo,
                  u.updated_at as author_avatar_version,
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
                group by p.id,p.author_id,u.nickname,u.student_id,u.profile_photo_key,u.updated_at order by p.created_at desc
                """, user.getId(), user.getRole().name(), user.getDepartmentCode(), user.getSectionCode(), user.getAcademicYear());
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
        if(file.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_MEDIA","Choose a media file to attach.");
        if(file.getSize()>50_000_000)throw new ApiException(HttpStatus.BAD_REQUEST,"MEDIA_TOO_LARGE","Media must be 50 MB or smaller.");
        UUID id=UUID.randomUUID();String status=user.getRole()==Role.SYSTEM_ADMIN?"APPROVED":"PENDING";String key=storage.save("posts/"+id,file);
        jdbc.update("insert into posts(id,author_id,body,visibility,status,department_code,section_code,academic_year,created_at,updated_at) values(?,?,?,?,?,?,?,?,now(),now())",id,user.getId(),body.trim(),"SCOPED",status,empty(department),empty(section),academicYear);
        jdbc.update("insert into post_media(post_id,storage_key,original_name,content_type,size_bytes) values(?,?,?,?,?)",id,key,file.getOriginalFilename(),file.getContentType(),file.getSize());
        if("PENDING".equals(status))events.approvalsChanged();return get(id,auth);
    }

    @GetMapping("/posts/{id}")
    public PostView get(@PathVariable UUID id, Authentication auth) {
        User user=currentUsers.require(auth);
        List<PostView> rows=posts(user, """
                select p.id,p.body,p.status,p.created_at,p.author_id,u.nickname,u.student_id,
                (u.profile_photo_key is not null) as author_has_profile_photo,
                u.updated_at as author_avatar_version,
                count(distinct case when r.reaction='LIKE' then r.user_id end) likes,
                count(distinct case when r.reaction='DISLIKE' then r.user_id end) dislikes,
                count(distinct c.id) comments,max(case when r.user_id=? then r.reaction end) my_reaction
                from posts p join users u on u.id=p.author_id left join post_reactions r on r.post_id=p.id
                left join comments c on c.post_id=p.id and c.deleted_at is null where p.id=? and p.deleted_at is null
                group by p.id,p.author_id,u.nickname,u.student_id,u.profile_photo_key,u.updated_at""",user.getId(),id);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"POST_NOT_FOUND","Post not found");return rows.getFirst();
    }

    /** Streams a post attachment only after the viewer has been allowed to load its post. */
    @GetMapping("/posts/{postId}/media/{mediaId}")
    public ResponseEntity<byte[]> media(@PathVariable UUID postId,@PathVariable UUID mediaId,Authentication auth) throws java.io.IOException {
        get(postId,auth);
        List<StoredMedia> items=jdbc.query("select storage_key,original_name,content_type from post_media where id=? and post_id=?",
                (rs,n)->new StoredMedia(rs.getString(1),rs.getString(2),rs.getString(3)),mediaId,postId);
        if(items.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"POST_MEDIA_NOT_FOUND","Post media was not found");
        StoredMedia item=items.getFirst();
        return ResponseEntity.ok().contentType(contentType(item.contentType())).body(storage.read(item.storageKey()));
    }

    @PutMapping("/posts/{id}/reaction")
    @Transactional
    public PostView react(@PathVariable UUID id,@Valid @RequestBody Reaction request,Authentication auth){User u=currentUsers.require(auth);get(id,auth);jdbc.update("insert into post_reactions(post_id,user_id,reaction) values(?,?,?) on conflict(post_id,user_id) do update set reaction=excluded.reaction,created_at=now()",id,u.getId(),request.type());return get(id,auth);}

    @DeleteMapping("/posts/{id}/reaction")
    @Transactional public PostView removeReaction(@PathVariable UUID id,Authentication auth){User u=currentUsers.require(auth);jdbc.update("delete from post_reactions where post_id=? and user_id=?",id,u.getId());return get(id,auth);}

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deletePost(@PathVariable UUID id,Authentication auth){
        User user=currentUsers.require(auth);
        int changed=jdbc.update("update posts set deleted_at=now(),updated_at=now() where id=? and deleted_at is null and (author_id=? or ?='SYSTEM_ADMIN')",id,user.getId(),user.getRole().name());
        if(changed==0)throw deleteFailure("posts",id);
        events.approvalsChanged();
    }

    @GetMapping("/posts/{id}/comments")
    public List<CommentView> comments(@PathVariable UUID id,Authentication auth){User user=currentUsers.require(auth);get(id,auth);return jdbc.query("""
            select c.id,c.body,c.created_at,c.author_id,u.nickname,u.student_id,
              (u.profile_photo_key is not null) as author_has_profile_photo,
              u.updated_at as author_avatar_version,c.parent_comment_id,
              count(distinct case when r.reaction='LIKE' then r.user_id end) likes,
              count(distinct case when r.reaction='DISLIKE' then r.user_id end) dislikes,
              max(case when r.user_id=? then r.reaction end) my_reaction
            from comments c join users u on u.id=c.author_id
            left join comment_reactions r on r.comment_id=c.id
            where c.post_id=? and c.deleted_at is null
            group by c.id,c.author_id,u.nickname,u.student_id,u.profile_photo_key,u.updated_at order by c.created_at
            """,(rs,n)->commentView(rs,user),user.getId(),id);}

    @PostMapping("/posts/{id}/comments") @ResponseStatus(HttpStatus.CREATED)
    @Transactional public CommentView comment(@PathVariable UUID id,@Valid @RequestBody CommentRequest request,Authentication auth){User u=currentUsers.require(auth);get(id,auth);if(request.parentCommentId()!=null){Integer count=jdbc.queryForObject("select count(*) from comments where id=? and post_id=? and deleted_at is null",Integer.class,request.parentCommentId(),id);if(count==null||count==0)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PARENT_COMMENT","The reply target is not a comment on this post");}UUID cid=UUID.randomUUID();jdbc.update("insert into comments(id,post_id,author_id,body,parent_comment_id,created_at,updated_at) values(?,?,?,?,?,now(),now())",cid,id,u.getId(),request.body().trim(),request.parentCommentId());return comments(id,auth).stream().filter(c->c.id().equals(cid)).findFirst().orElseThrow();}

    @PutMapping("/comments/{id}/reaction")
    @Transactional public CommentView reactToComment(@PathVariable UUID id,@Valid @RequestBody Reaction request,Authentication auth){User u=currentUsers.require(auth);UUID postId=commentPostId(id);get(postId,auth);jdbc.update("insert into comment_reactions(comment_id,user_id,reaction) values(?,?,?) on conflict(comment_id,user_id) do update set reaction=excluded.reaction,created_at=now()",id,u.getId(),request.type());return comments(postId,auth).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}

    @DeleteMapping("/comments/{id}/reaction")
    @Transactional public CommentView removeCommentReaction(@PathVariable UUID id,Authentication auth){User u=currentUsers.require(auth);UUID postId=commentPostId(id);get(postId,auth);jdbc.update("delete from comment_reactions where comment_id=? and user_id=?",id,u.getId());return comments(postId,auth).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteComment(@PathVariable UUID id,Authentication auth){
        User user=currentUsers.require(auth);
        int changed=jdbc.update("update comments set deleted_at=now(),updated_at=now() where id=? and deleted_at is null and (author_id=? or ?='SYSTEM_ADMIN')",id,user.getId(),user.getRole().name());
        if(changed==0)throw deleteFailure("comments",id);
    }

    private List<PostView> posts(User viewer,String sql,Object... args){
        List<PostView> result=jdbc.query(sql,(rs,n)->post(rs,viewer),args);
        List<PostView> withMedia=new ArrayList<>();
        for(PostView post:result)withMedia.add(new PostView(post.id(),post.body(),post.status(),post.createdAt(),post.authorId(),post.authorNickname(),post.authorStudentId(),post.authorHasProfilePhoto(),post.authorAvatarVersion(),post.likes(),post.dislikes(),post.comments(),post.myReaction(),post.canDelete(),mediaFor(post.id())));
        return withMedia;
    }
    private PostView post(ResultSet rs,User viewer)throws SQLException{UUID authorId=rs.getObject("author_id",UUID.class);java.time.OffsetDateTime avatarVersion=rs.getObject("author_avatar_version",java.time.OffsetDateTime.class);return new PostView(rs.getObject("id",UUID.class),rs.getString("body"),rs.getString("status"),rs.getObject("created_at",java.time.OffsetDateTime.class).toInstant(),authorId,rs.getString("nickname"),rs.getString("student_id"),rs.getBoolean("author_has_profile_photo"),avatarVersion==null?null:avatarVersion.toInstant(),rs.getLong("likes"),rs.getLong("dislikes"),rs.getLong("comments"),rs.getString("my_reaction"),canDelete(viewer,authorId),List.of());}
    private List<MediaView> mediaFor(UUID postId){return jdbc.query("select id,original_name,content_type,size_bytes from post_media where post_id=? order by created_at",(rs,n)->new MediaView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),postId);}
    private CommentView commentView(ResultSet rs,User viewer)throws SQLException{UUID authorId=rs.getObject("author_id",UUID.class);java.time.OffsetDateTime avatarVersion=rs.getObject("author_avatar_version",java.time.OffsetDateTime.class);return new CommentView(rs.getObject("id",UUID.class),rs.getString("body"),rs.getObject("created_at",java.time.OffsetDateTime.class).toInstant(),authorId,rs.getString("nickname"),rs.getString("student_id"),rs.getBoolean("author_has_profile_photo"),avatarVersion==null?null:avatarVersion.toInstant(),rs.getObject("parent_comment_id",UUID.class),rs.getLong("likes"),rs.getLong("dislikes"),rs.getString("my_reaction"),canDelete(viewer,authorId));}
    private boolean canDelete(User viewer,UUID authorId){return viewer.getId().equals(authorId)||viewer.getRole()==Role.SYSTEM_ADMIN;}
    private ApiException deleteFailure(String table,UUID id){
        Integer exists=jdbc.queryForObject("select count(*) from "+table+" where id=? and deleted_at is null",Integer.class,id);
        if(exists==null||exists==0)return new ApiException(HttpStatus.NOT_FOUND,table.equals("posts")?"POST_NOT_FOUND":"COMMENT_NOT_FOUND",table.equals("posts")?"Post not found":"Comment not found");
        return new ApiException(HttpStatus.FORBIDDEN,"DELETE_NOT_ALLOWED","You can only delete your own "+(table.equals("posts")?"posts":"comments")+".");
    }
    private UUID commentPostId(UUID id){List<UUID> rows=jdbc.query("select post_id from comments where id=? and deleted_at is null",(rs,n)->rs.getObject(1,UUID.class),id);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"COMMENT_NOT_FOUND","Comment not found");return rows.getFirst();}
    private String empty(String s){return s==null||s.isBlank()?null:s;}
    private MediaType contentType(String value){if(value==null||value.isBlank())return MediaType.APPLICATION_OCTET_STREAM;try{return MediaType.parseMediaType(value);}catch(IllegalArgumentException ignored){return MediaType.APPLICATION_OCTET_STREAM;}}
    public record CreatePost(@NotBlank @Size(max=2000) String body,String department,String section,Integer academicYear){}
    public record Reaction(@Pattern(regexp="LIKE|DISLIKE") String type){}
    public record CommentRequest(@NotBlank @Size(max=1000) String body,UUID parentCommentId){}
    public record PostView(UUID id,String body,String status,Instant createdAt,UUID authorId,String authorNickname,String authorStudentId,boolean authorHasProfilePhoto,Instant authorAvatarVersion,long likes,long dislikes,long comments,String myReaction,boolean canDelete,List<MediaView> media){}
    public record MediaView(UUID id,String originalName,String contentType,long sizeBytes){}
    public record CommentView(UUID id,String body,Instant createdAt,UUID authorId,String authorNickname,String authorStudentId,boolean authorHasProfilePhoto,Instant authorAvatarVersion,UUID parentCommentId,long likes,long dislikes,String myReaction,boolean canDelete){}
    private record StoredMedia(String storageKey,String originalName,String contentType){}
}
