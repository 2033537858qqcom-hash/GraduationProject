package cn.zzuit.campus.content;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private static final String SELECT="""
        SELECT c.*,p.content_id live_id,p.slug live_slug,p.category live_category,p.title live_title,
          p.summary live_summary,p.body live_body,p.source_url live_source_url,p.source_type live_source_type,
          p.version live_version,p.published_at live_published_at,p.visible live_visible
        FROM website_content c LEFT JOIN content_publication p ON p.content_id=c.id
        """;
    private static final String HISTORY="""
        SELECT a.content_version version,a.action,a.reason,COALESCE(a.actor_name,u.full_name) actor_name,a.created_at recorded_at,
          CASE WHEN r.content_id IS NULL THEN FALSE ELSE TRUE END has_snapshot
        FROM content_audit a JOIN app_user u ON u.id=a.actor_id
        LEFT JOIN content_revision r ON r.content_id=a.content_id AND r.version=a.content_version WHERE a.content_id=:id
        UNION ALL
        SELECT r.version,r.action,r.reason,r.actor_name,r.recorded_at,TRUE has_snapshot FROM content_revision r
        WHERE r.content_id=:id AND NOT EXISTS(SELECT 1 FROM content_audit a WHERE a.content_id=r.content_id AND a.content_version=r.version)
        """;
    public ContentService(JdbcClient jdbc,IdentityService identities) { this.jdbc=jdbc; this.identities=identities; }
    public List<Content> published(String category) {
        var query=jdbc.sql("SELECT * FROM content_publication WHERE visible=TRUE"+(category==null?"":" AND category=:category")+" ORDER BY published_at DESC,content_id DESC");
        if(category!=null) query.param("category",category);
        return query.query(this::publicRow).list();
    }
    public Content publicDetail(String slug) { return jdbc.sql("SELECT * FROM content_publication WHERE slug=:slug AND visible=TRUE").param("slug",slug).query(this::publicRow).optional().orElseThrow(this::notFound); }
    public List<Content> all(CampusPrincipal user) { readable(user); return jdbc.sql(SELECT+" ORDER BY c.updated_at DESC,c.id DESC").query(this::map).list(); }
    private void readable(CampusPrincipal user) {
        var current=identities.current(user.id()).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","请重新登录"));
        if(current.permissions().contains("content:edit")) identities.requirePermission(user,"content:edit"); else identities.requirePermission(user,"content:review");
    }
    private Content locked(long id,CampusPrincipal user,String permission,int version) {
        jdbc.sql("SELECT id FROM website_content WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().orElseThrow(this::notFound);
        identities.requirePermission(user,permission); var item=detail(id); if(item.version()!=version) throw conflict(); return item;
    }
    private void owner(Content item,CampusPrincipal user) { if(!Objects.equals(item.createdBy(),user.id())) throw conflict(); }
    private void independent(Content item,CampusPrincipal user) { if(Objects.equals(item.createdBy(),user.id())) throw new ApiException(HttpStatus.FORBIDDEN,"SELF_REVIEW_FORBIDDEN","请由另一位有审核权限的人员处理"); }
    private void state(Content item,String... allowed) { if(!List.of(allowed).contains(item.status())) throw conflict(); }

    @Transactional public Content create(ContentInput input,CampusPrincipal user) {
        identities.requirePermission(user,"content:edit"); validateSource(input);
        jdbc.sql("""
            INSERT INTO website_content(slug,category,title,summary,body,source_url,source_type,status,workflow_status,created_by)
            VALUES(:slug,:category,:title,:summary,:body,:url,:type,'DRAFT','DRAFT',:user)
            """).param("slug",input.slug()).param("category",input.category()).param("title",input.title()).param("summary",input.summary())
            .param("body",input.body()).param("url",input.sourceUrl()).param("type",input.sourceType()).param("user",user.id()).update();
        var created=jdbc.sql(SELECT+" WHERE c.slug=:slug").param("slug",input.slug()).query(this::map).single(); audit(created,user,"CREATED","创建草稿"); return created;
    }
    @Transactional public Content update(long id,ContentInput input,CampusPrincipal user) {
        var item=locked(id,user,"content:edit",input.version()); owner(item,user); state(item,"DRAFT","REJECTED"); validateSource(input);
        if(item.live()!=null && !item.slug().equals(input.slug())) throw new ApiException(HttpStatus.CONFLICT,"PUBLISHED_SLUG_LOCKED","已发布过的内容标识不可修改，以保留官网链接");
        int count=jdbc.sql("""
            UPDATE website_content SET slug=:slug,category=:category,title=:title,summary=:summary,body=:body,source_url=:url,
              source_type=:type,status='DRAFT',workflow_status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version
            """).param("slug",input.slug()).param("category",input.category()).param("title",input.title()).param("summary",input.summary()).param("body",input.body())
            .param("url",input.sourceUrl()).param("type",input.sourceType()).param("id",id).param("version",input.version()).update();
        if(count!=1) throw conflict(); var updated=detail(id); audit(updated,user,"UPDATED","保存工作稿"); return updated;
    }
    @Transactional public Content submit(long id,int version,CampusPrincipal user) {
        var item=locked(id,user,"content:edit",version); owner(item,user); state(item,"DRAFT","REJECTED");
        return transition(item,user,"IN_REVIEW","SUBMITTED","送交审核",null,item.changeReason());
    }
    @Transactional public Content recall(long id,int version,String reason,CampusPrincipal user) {
        var item=locked(id,user,"content:edit",version); owner(item,user); state(item,"IN_REVIEW"); return transition(item,user,"DRAFT","RECALLED",reason,null,item.changeReason());
    }
    @Transactional public Content reject(long id,int version,String reason,CampusPrincipal user) {
        var item=locked(id,user,"content:review",version); independent(item,user); state(item,"IN_REVIEW"); return transition(item,user,"REJECTED","REJECTED",reason,reason,item.changeReason());
    }
    @Transactional public Content revise(long id,int version,String reason,CampusPrincipal user) {
        var item=locked(id,user,"content:edit",version); state(item,"PUBLISHED","WITHDRAWN");
        jdbc.sql("UPDATE website_content SET created_by=:user,reviewed_by=NULL WHERE id=:id").param("user",user.id()).param("id",id).update();
        return transition(item,user,"DRAFT","REVISION_STARTED",reason,null,reason);
    }
    @Transactional public Content publish(long id,int version,CampusPrincipal user) {
        var item=locked(id,user,"content:review",version); independent(item,user); state(item,"IN_REVIEW");
        jdbc.sql("UPDATE website_content SET reviewed_by=:user,published_at=CURRENT_TIMESTAMP WHERE id=:id").param("user",user.id()).param("id",id).update();
        var changed=transition(item,user,"PUBLISHED","PUBLISHED",item.changeReason()==null?"审核通过并发布":"审核通过更正："+item.changeReason(),null,item.changeReason());
        if(jdbc.sql("SELECT COUNT(*) FROM content_publication WHERE content_id=:id").param("id",id).query(Long.class).single()==0) {
            jdbc.sql("""
                INSERT INTO content_publication(content_id,slug,category,title,summary,body,source_url,source_type,version,published_at,visible)
                SELECT id,slug,category,title,summary,body,source_url,source_type,version,published_at,TRUE FROM website_content WHERE id=:id
                """).param("id",id).update();
        } else {
            jdbc.sql("""
                UPDATE content_publication SET slug=:slug,category=:category,title=:title,summary=:summary,body=:body,
                  source_url=:url,source_type=:type,version=:version,published_at=:time,visible=TRUE WHERE content_id=:id
                """).param("slug",changed.slug()).param("category",changed.category()).param("title",changed.title()).param("summary",changed.summary()).param("body",changed.body())
                .param("url",changed.sourceUrl()).param("type",changed.sourceType()).param("version",changed.version()).param("time",java.sql.Timestamp.valueOf(changed.publishedAt())).param("id",id).update();
        }
        return detail(id);
    }
    @Transactional public Content unpublish(long id,int version,String reason,CampusPrincipal user) {
        var item=locked(id,user,"content:review",version); if(item.live()==null || !item.live().visible()) throw conflict();
        jdbc.sql("UPDATE content_publication SET visible=FALSE WHERE content_id=:id").param("id",id).update();
        return transition(item,user,"WITHDRAWN","UNPUBLISHED",reason,reason,item.changeReason());
    }
    private Content transition(Content item,CampusPrincipal user,String state,String action,String reason,String reviewReason,String changeReason) {
        String legacy=List.of("PUBLISHED","IN_REVIEW").contains(state)?state:"DRAFT";
        if(jdbc.sql("UPDATE website_content SET status=:legacy,workflow_status=:state,review_reason=:review,change_reason=:change,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version")
            .param("legacy",legacy).param("state",state).param("review",reviewReason).param("change",changeReason).param("id",item.id()).param("version",item.version()).update()!=1) throw conflict();
        var changed=detail(item.id()); audit(changed,user,action,reason.trim()); return changed;
    }
    public HistoryPage history(long id,CampusPrincipal user,int page,int size) {
        readable(user); detail(id); if(page<1 || page>1000000 || size<1 || size>100) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGE","页码须大于零，每页最多 100 条");
        long total=jdbc.sql("SELECT COUNT(*) FROM ("+HISTORY+") records").param("id",id).query(Long.class).single();
        var items=jdbc.sql("SELECT * FROM ("+HISTORY+") records ORDER BY version DESC LIMIT :limit OFFSET :offset").param("id",id).param("limit",size).param("offset",((long)page-1)*size)
            .query((r,n)->new HistoryItem(r.getInt("version"),r.getString("action"),r.getString("reason"),r.getString("actor_name"),r.getTimestamp("recorded_at").toLocalDateTime(),r.getBoolean("has_snapshot"))).list();
        return new HistoryPage(items,total,page,size);
    }
    public Revision revision(long id,int version,CampusPrincipal user) {
        readable(user);
        return jdbc.sql("SELECT * FROM content_revision WHERE content_id=:id AND version=:version").param("id",id).param("version",version)
            .query((r,n)->new Revision(new Content(id,r.getString("slug"),r.getString("category"),r.getString("title"),r.getString("summary"),r.getString("body"),r.getString("source_url"),r.getString("source_type"),r.getString("workflow_status"),r.getInt("version"),r.getObject("created_by",Long.class),time(r,"published_at"),r.getString("review_reason"),r.getString("change_reason"),null),r.getString("action"),r.getString("reason"),r.getString("actor_name"),r.getTimestamp("recorded_at").toLocalDateTime())).optional().orElseThrow(this::notFound);
    }
    private Content detail(long id) { return jdbc.sql(SELECT+" WHERE c.id=:id").param("id",id).query(this::map).optional().orElseThrow(this::notFound); }
    private void audit(Content content,CampusPrincipal user,String action,String reason) {
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",user.id()).query(String.class).single();
        jdbc.sql("INSERT INTO content_audit(content_id,actor_id,action,content_version,reason,actor_name) VALUES(:id,:actor,:action,:version,:reason,:name)")
            .param("id",content.id()).param("actor",user.id()).param("action",action).param("version",content.version()).param("reason",reason).param("name",name).update();
        jdbc.sql("""
            INSERT INTO content_revision(content_id,version,slug,category,title,summary,body,source_url,source_type,workflow_status,created_by,published_at,review_reason,change_reason,action,reason,actor_name)
            SELECT id,version,slug,category,title,summary,body,source_url,source_type,workflow_status,created_by,published_at,review_reason,change_reason,:action,:reason,:name FROM website_content WHERE id=:id
            """).param("id",content.id()).param("action",action).param("reason",reason).param("name",name).update();
    }
    private void validateSource(ContentInput input) {
        if("PUBLIC_SOURCE".equals(input.sourceType()) && (input.sourceUrl()==null || input.sourceUrl().isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST,"SOURCE_REQUIRED","公开学校资料需要填写来源链接");
        if(input.sourceUrl()!=null && !input.sourceUrl().isBlank()) {
            try { var uri=URI.create(input.sourceUrl()); if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null) throw new IllegalArgumentException(); }
            catch(IllegalArgumentException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_SOURCE_URL","来源链接必须是有效的 HTTP 或 HTTPS 地址"); }
        }
    }
    private LocalDateTime time(java.sql.ResultSet r,String column) throws java.sql.SQLException { var value=r.getTimestamp(column); return value==null?null:value.toLocalDateTime(); }
    private Content map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        Snapshot live=r.getObject("live_id")==null?null:new Snapshot(r.getString("live_slug"),r.getString("live_category"),r.getString("live_title"),r.getString("live_summary"),r.getString("live_body"),r.getString("live_source_url"),r.getString("live_source_type"),r.getInt("live_version"),time(r,"live_published_at"),r.getBoolean("live_visible"));
        return new Content(r.getLong("id"),r.getString("slug"),r.getString("category"),r.getString("title"),r.getString("summary"),r.getString("body"),r.getString("source_url"),r.getString("source_type"),r.getString("workflow_status"),r.getInt("version"),r.getObject("created_by",Long.class),time(r,"published_at"),r.getString("review_reason"),r.getString("change_reason"),live);
    }
    private Content publicRow(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Content(r.getLong("content_id"),r.getString("slug"),r.getString("category"),r.getString("title"),r.getString("summary"),r.getString("body"),r.getString("source_url"),r.getString("source_type"),"PUBLISHED",r.getInt("version"),null,time(r,"published_at"),null,null,null); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"CONTENT_NOT_FOUND","内容不存在或尚未发布"); }
    private ApiException conflict() { return new ApiException(HttpStatus.CONFLICT,"STALE_CONTENT","状态、版本或编辑归属已变化，请刷新后重新选择内容"); }
    public record Snapshot(String slug,String category,String title,String summary,String body,String sourceUrl,String sourceType,int version,LocalDateTime publishedAt,boolean visible) {}
    public record Content(long id,String slug,String category,String title,String summary,String body,String sourceUrl,String sourceType,String status,int version,Long createdBy,LocalDateTime publishedAt,String reviewReason,String changeReason,Snapshot live) {}
    public record HistoryItem(int version,String action,String reason,String actorName,LocalDateTime recordedAt,boolean hasSnapshot) {}
    public record HistoryPage(List<HistoryItem> items,long total,int page,int pageSize) {}
    public record Revision(Content content,String action,String reason,String actorName,LocalDateTime recordedAt) {}
    public record ContentInput(@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{1,79}") String slug,@NotBlank @Pattern(regexp="about|news|notice") String category,
        @NotBlank @Size(max=160) String title,@NotBlank @Size(max=400) String summary,@NotBlank @Size(max=20000) String body,@Size(max=1000) String sourceUrl,
        @NotBlank @Pattern(regexp="PUBLIC_SOURCE|DEMO") String sourceType,@Min(0) int version) {}
}
