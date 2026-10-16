package cn.zzuit.campus.notice;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveService;
import cn.zzuit.campus.certificate.CertificateService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class NoticeService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final LeaveService leaves;
    private final CertificateService certificates;
    private final cn.zzuit.campus.repair.RepairService repairs;
    private final cn.zzuit.campus.housing.HousingTransferService housing;
    public NoticeService(JdbcClient jdbc,IdentityService identities,LeaveService leaves,CertificateService certificates,cn.zzuit.campus.repair.RepairService repairs,cn.zzuit.campus.housing.HousingTransferService housing) {
        this.jdbc=jdbc; this.identities=identities; this.leaves=leaves; this.certificates=certificates;this.repairs=repairs;this.housing=housing;
    }
    private void current(CampusPrincipal user) {
        var fresh=identities.current(user.id()).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","请重新登录"));
        if(fresh.securityVersion()!=user.securityVersion()) throw new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","身份已变化，请重新登录");
    }
    public Page list(CampusPrincipal user,Boolean read,int page,int size) {
        current(user);
        if(page<1 || page>1000000 || size<1 || size>100) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGE","分页参数无效");
        String where=" WHERE user_id=:u"+(read==null?"":read?" AND read_at IS NOT NULL":" AND read_at IS NULL");
        long total=jdbc.sql("SELECT COUNT(*) FROM user_notice"+where).param("u",user.id()).query(Long.class).single();
        long unread=jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE user_id=:u AND read_at IS NULL").param("u",user.id()).query(Long.class).single();
        var rows=jdbc.sql("SELECT * FROM user_notice"+where+" ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
            .param("u",user.id()).param("limit",size).param("offset",((long)page-1)*size)
            .query((r,n)->new Notice(r.getLong("id"),r.getString("title"),r.getString("body"),r.getTimestamp("read_at")!=null,r.getTimestamp("created_at").toLocalDateTime(),
                target(user,r.getString("source_type"),r.getObject("source_id",Long.class),r.getString("source_view")))).list();
        return new Page(rows,total,unread,page,size);
    }
    private String target(CampusPrincipal user,String type,Long id,String view) {
        if(type==null || id==null || view==null) return null;
        String mode=view.equals("MINE")?"mine":"review";
        try {
            if(type.equals("HOUSING") && List.of("MINE","REVIEW","ASSIGN").contains(view)) {
                housing.requireNoticeAccess(user,id,view);
                return (view.equals("MINE")?"/housing/transfer":view.equals("REVIEW")?"/student-affairs/housing":"/housing/assignments")+"?id="+id+"&tab=details";
            }
            if(type.equals("REPAIR") && List.of("MINE","DISPATCH","WORK").contains(view)) {
                repairs.detail(user,view.toLowerCase(java.util.Locale.ROOT),id);
                return (view.equals("MINE")?"/repairs/mine":view.equals("DISPATCH")?"/logistics/repairs/dispatch":"/logistics/repairs/tasks")+"?id="+id+"&tab=details";
            }
            if(type.equals("LEAVE")) { leaves.detail(user,mode,id); return (mode.equals("mine")?"/portal/leave":"/student-affairs/leave")+"?id="+id+"&tab=details"; }
            if(type.equals("CERTIFICATE")) { certificates.detail(user,mode,id); return (mode.equals("mine")?"/portal/certificates":"/student-affairs/certificates")+"?id="+id+"&tab=details"; }
            if(type.equals("RESERVATION")) { return (view.equals("MINE")?"/portal/reservations":"/student-affairs/reservations")+"?id="+id+"&tab=details"; }
        } catch(ApiException ex) { return null; }
        return null;
    }
    public void read(CampusPrincipal user,long id) {
        current(user);
        int count=jdbc.sql("UPDATE user_notice SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=:id AND user_id=:u")
            .param("id",id).param("u",user.id()).update();
        if(count==0) throw new ApiException(HttpStatus.NOT_FOUND,"NOTICE_NOT_FOUND","通知不存在");
    }
    public record Notice(long id,String title,String body,boolean read,LocalDateTime createdAt,String targetPath) {}
    public record Page(List<Notice> items,long total,long unreadCount,int page,int pageSize) {}
}
