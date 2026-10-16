package cn.zzuit.campus.affairs;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.housing.HousingTransferService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AffairsController {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final HousingTransferService housing;
    private static final Map<String,String> PERMISSIONS=Map.of("LEAVE","leave:apply","CERTIFICATE","certificate:apply","REPAIR","repair:apply","HOUSING","housing:read","RESERVATION","reservation:apply");
    private static final String UNION="""
        SELECT 'LEAVE' kind,id,student_id,status,version,created_at,updated_at FROM leave_request
        UNION ALL SELECT 'CERTIFICATE' kind,id,student_id,status,version,created_at,updated_at FROM certificate_request
        UNION ALL SELECT 'REPAIR' kind,id,reporter_id student_id,status,version,created_at,updated_at FROM repair_request
        UNION ALL SELECT 'HOUSING' kind,id,student_id,status,version,created_at,updated_at FROM housing_transfer
        UNION ALL SELECT 'RESERVATION' kind,id,applicant_id student_id,status,version,created_at,updated_at FROM venue_reservation
        """;
    public AffairsController(JdbcClient jdbc,IdentityService identities,HousingTransferService housing) { this.jdbc=jdbc; this.identities=identities; this.housing=housing; }
    @GetMapping("/api/affairs/mine")
    Page list(@AuthenticationPrincipal CampusPrincipal user,@RequestParam(required=false) String kind,@RequestParam(required=false) String status,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        identities.requirePermission(user,"portal:read");
        if(page<1 || page>1000000 || pageSize<1 || pageSize>100) bad("分页参数无效");
        if(kind!=null && !PERMISSIONS.containsKey(kind)) bad("办事种类无效");
        if(status!=null && !List.of("DRAFT","SUBMITTED","RETURNED","APPROVED","COMPLETED","REJECTED","WITHDRAWN","CANCELLED","ASSIGNED","IN_PROGRESS","AWAITING_CONFIRMATION","AWAITING_ASSIGNMENT","AWAITING_CHECKIN","EXPIRED").contains(status)) bad("办事状态无效");
        var permissions=identities.permissions(user.id());
        if(kind!=null) identities.requirePermission(user,PERMISSIONS.get(kind));
        var kinds=PERMISSIONS.entrySet().stream().filter(entry->permissions.contains(entry.getValue())).map(Map.Entry::getKey).toList();
        if(kinds.contains("HOUSING")) housing.refreshMine(user);
        String table=" FROM ("+UNION+") a", owner=" WHERE student_id=:u AND kind IN (:kinds)", where=owner;
        var params=new HashMap<String,Object>(); params.put("u",user.id()); params.put("kinds",kinds.isEmpty()?List.of("NONE"):kinds);
        if(kind!=null) { where+=" AND kind=:kind"; params.put("kind",kind); }
        if(status!=null) { where+=" AND status=:status"; params.put("status",status); }
        long total=jdbc.sql("SELECT COUNT(*)"+table+where).params(params).query(Long.class).single();
        var rows=jdbc.sql("SELECT *"+table+where+" ORDER BY updated_at DESC,id DESC,kind LIMIT :limit OFFSET :offset")
            .params(params).param("limit",pageSize).param("offset",((long)page-1)*pageSize)
            .query((r,n)->new Item(r.getString("kind"),r.getLong("id"),name(r.getString("kind")),r.getString("status"),r.getInt("version"),
                r.getTimestamp("created_at").toLocalDateTime(),r.getTimestamp("updated_at").toLocalDateTime(),
                path(r.getString("kind"))+"?id="+r.getLong("id")+"&tab=details")).list();
        var summary=jdbc.sql("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN status='DRAFT' THEN 1 ELSE 0 END),0) drafts,COALESCE(SUM(CASE WHEN status IN ('SUBMITTED','ASSIGNED','IN_PROGRESS','AWAITING_CONFIRMATION','AWAITING_ASSIGNMENT','AWAITING_CHECKIN') THEN 1 ELSE 0 END),0) pending,COALESCE(SUM(CASE WHEN status='RETURNED' THEN 1 ELSE 0 END),0) returned,COALESCE(SUM(CASE WHEN status IN ('APPROVED','COMPLETED','REJECTED','WITHDRAWN','CANCELLED','EXPIRED') THEN 1 ELSE 0 END),0) finished"+table+owner)
            .param("u",user.id()).param("kinds",params.get("kinds")).query((r,n)->new Summary(r.getLong("total"),r.getLong("drafts"),r.getLong("pending"),r.getLong("returned"),r.getLong("finished"))).single();
        return new Page(rows,total,page,pageSize,summary);
    }
    private String name(String kind) { return switch(kind) {case "LEAVE"->"请假申请";case "REPAIR"->"校园报修";case "HOUSING"->"调宿申请";case "RESERVATION"->"场地借用预约";default->"在校证明申请";}; }
    private String path(String kind) { return switch(kind) {case "LEAVE"->"/portal/leave";case "REPAIR"->"/repairs/mine";case "HOUSING"->"/housing/transfer";case "RESERVATION"->"/portal/reservations";default->"/portal/certificates";}; }
    private void bad(String message) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_AFFAIRS",message); }
    public record Item(String kind,long id,String name,String status,int version,LocalDateTime createdAt,LocalDateTime updatedAt,String targetPath) {}
    public record Summary(long total,long drafts,long pending,long returned,long finished) {}
    public record Page(List<Item> items,long total,int page,int pageSize,Summary summary) {}
}
