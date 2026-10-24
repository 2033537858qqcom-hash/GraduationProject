package cn.zzuit.campus.affairs;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveService;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.repair.RepairService;
import cn.zzuit.campus.housing.HousingTransferService;
import cn.zzuit.campus.reservation.VenueReservationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AffairTimelineService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final LeaveService leaves;
    private final CertificateService certificates;
    private final RepairService repairs;
    private final HousingTransferService housing;
    private final VenueReservationService reservations;
    private static final Map<String,String> PERMISSIONS=Map.of("LEAVE","leave:apply","CERTIFICATE","certificate:apply","REPAIR","repair:apply","HOUSING","housing:read","RESERVATION","reservation:apply");
    private static final Map<String,String> HOUSING_TITLES=Map.ofEntries(
        Map.entry("SAVE","保存调宿资料"),Map.entry("SUBMIT","提交调宿申请"),Map.entry("SUPPLEMENT","补交并重新提交"),Map.entry("WITHDRAW","撤回调宿申请"),
        Map.entry("RETURN","学院退回补交"),Map.entry("APPROVE","学院初审通过"),Map.entry("REJECT","学院未通过初审"),Map.entry("RESERVE","宿管预留床位"),
        Map.entry("CHECK_IN","确认调宿入住"),Map.entry("CANCEL_REQUEST","申请取消预留"),Map.entry("ACCEPT_CANCEL","宿管同意取消"),Map.entry("CANCEL","取消调宿预留"),
        Map.entry("KEEP","宿管保留原预留"),Map.entry("EXTEND","调整入住期限"),Map.entry("RELEASE","释放床位，等待重新安排"),Map.entry("EXPIRE","预留到期释放"),
        Map.entry("MATERIAL_UPLOAD","添加待提交材料"),Map.entry("MATERIAL_REMOVE","移出当前材料清单"));
    private static final String HOUSING_LOGS="""
        SELECT 'OPERATION' source,id,action,created_at FROM housing_transfer_action WHERE transfer_id=:id
        UNION ALL
        SELECT 'SYSTEM' source,id,'EXPIRE' action,created_at FROM foundation_audit WHERE entity_type='HOUSING_TRANSFER' AND entity_id=:id AND action='TRANSFER_EXPIRE'
        """;
    public AffairTimelineService(JdbcClient jdbc,IdentityService identities,LeaveService leaves,CertificateService certificates,RepairService repairs,HousingTransferService housing,VenueReservationService reservations) {
        this.jdbc=jdbc;this.identities=identities;this.leaves=leaves;this.certificates=certificates;this.repairs=repairs;this.housing=housing;this.reservations=reservations;
    }
    public record Event(String key,String action,String title,String actor,String comment,String status,Integer version,LocalDateTime createdAt) {}
    public record Timeline(String kind,long requestId,String status,int version,LocalDateTime updatedAt,String nextStep,List<Event> items,long total,int page,int pageSize,String historyNote) {}
    private record Current(String status,int version,LocalDateTime updatedAt,boolean cancellationPending,String restriction) {}

    @Transactional public Timeline mine(CampusPrincipal user,String kind,long id,int page,int size,Integer expectedVersion) {
        identities.requirePermission(user,"portal:read");
        if(!PERMISSIONS.containsKey(kind) || id<1 || page<1 || page>1000000 || size<1 || size>50 || expectedVersion!=null && expectedVersion<0)throw invalid();
        identities.requirePermission(user,PERMISSIONS.get(kind));
        // Same lock order as the business writes: one response contains one current revision.
        jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        var current=current(user,kind,id);
        if(expectedVersion!=null && expectedVersion!=current.version())throw new ApiException(HttpStatus.CONFLICT,"AFFAIR_TIMELINE_CHANGED","申请已有新的办理记录，请重新读取最新进度");
        long total;List<Event> items;String note="按实际留存记录展示，从近到远排列。";
        if(kind.equals("HOUSING")) {
            total=jdbc.sql("SELECT COUNT(*) FROM ("+HOUSING_LOGS+") h").param("id",id).query(Long.class).single();
            items=jdbc.sql("SELECT * FROM ("+HOUSING_LOGS+") h ORDER BY created_at DESC,source DESC,id DESC LIMIT :limit OFFSET :offset")
                .param("id",id).param("limit",size).param("offset",((long)page-1)*size)
                .query((r,n)->housingEvent(r.getString("source"),r.getLong("id"),r.getString("action"),r.getTimestamp("created_at").toLocalDateTime())).list();
            note="从近到远展示实际调宿操作。原日志未保存当时状态和版本，故不补造历史状态；材料内容请在原申请核对。";
        } else if(kind.equals("RESERVATION")) {
            total=jdbc.sql("SELECT COUNT(*) FROM venue_reservation_action WHERE reservation_id=:id").param("id",id).query(Long.class).single();
            items=jdbc.sql("SELECT r.id,r.action,r.actor_name,r.comment,r.status,r.reservation_version request_version,r.created_at,(SELECT p.status FROM venue_reservation_action p WHERE p.reservation_id=r.reservation_id AND p.id<r.id ORDER BY p.id DESC LIMIT 1) previous_status FROM venue_reservation_action r WHERE r.reservation_id=:id ORDER BY r.id DESC LIMIT :limit OFFSET :offset")
                .param("id",id).param("limit",size).param("offset",((long)page-1)*size)
                .query((r,n)->new Event(kind+"-"+r.getLong("id"),r.getString("action"),title(kind,r.getString("action"),r.getString("previous_status")),r.getString("actor_name"),r.getString("comment"),r.getString("status"),r.getInt("request_version"),r.getTimestamp("created_at").toLocalDateTime())).list();
            note="从近到远展示场地与设备借用申请审核轨迹。";
        } else {
            String table=kind.equals("LEAVE")?"leave_revision":kind.equals("CERTIFICATE")?"certificate_revision":"repair_action";
            total=jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE request_id=:id").param("id",id).query(Long.class).single();
            items=jdbc.sql("SELECT r.id,r.action,r.actor_name,r.comment,r.status,r.request_version,r.created_at,(SELECT p.status FROM "+table+" p WHERE p.request_id=r.request_id AND p.id<r.id ORDER BY p.id DESC LIMIT 1) previous_status FROM "+table+" r WHERE r.request_id=:id ORDER BY r.id DESC LIMIT :limit OFFSET :offset")
                .param("id",id).param("limit",size).param("offset",((long)page-1)*size)
                .query((r,n)->new Event(kind+"-"+r.getLong("id"),r.getString("action"),title(kind,r.getString("action"),r.getString("previous_status")),r.getString("actor_name"),r.getString("comment"),r.getString("status"),r.getInt("request_version"),r.getTimestamp("created_at").toLocalDateTime())).list();
        }
        return new Timeline(kind,id,current.status(),current.version(),current.updatedAt(),nextStep(kind,current),items,total,page,size,note);
    }
    private Current current(CampusPrincipal user,String kind,long id) {
        return switch(kind) {
            case "LEAVE"->{var request=leaves.detail(user,"mine",id);yield new Current(request.status(),request.version(),request.updatedAt(),false,null);}
            case "CERTIFICATE"->{var request=certificates.detail(user,"mine",id);yield new Current(request.status(),request.version(),request.updatedAt(),false,null);}
            case "REPAIR"->{var request=repairs.detail(user,"mine",id);yield new Current(request.status(),request.version(),request.updatedAt(),request.cancellationPending(),request.locationEligible()?null:request.locationEligibilityMessage());}
            case "HOUSING"->{
                // Authorize this exact request before refreshing the owner's expired reservations.
                housing.requireNoticeAccess(user,id,"MINE");housing.refreshMine(user);
                yield jdbc.sql("SELECT status,version,updated_at,cancel_requested FROM housing_transfer WHERE id=:id AND student_id=:user").param("id",id).param("user",user.id())
                    .query((r,n)->new Current(r.getString(1),r.getInt(2),r.getTimestamp(3).toLocalDateTime(),r.getBoolean(4),null)).single();
            }
            case "RESERVATION"->{var request=reservations.getDetail(user,id);yield new Current(request.status(),request.version(),request.updatedAt(),false,null);}
            default->throw invalid();
        };
    }
    private Event housingEvent(String source,long id,String action,LocalDateTime created) {
        String actor=switch(action) {case "RETURN","APPROVE","REJECT"->"学院办理人员";case "RESERVE","CHECK_IN","ACCEPT_CANCEL","CANCEL","KEEP","EXTEND","RELEASE"->"楼栋宿管";case "EXPIRE"->"系统";case "SAVE","SUBMIT","SUPPLEMENT","WITHDRAW","CANCEL_REQUEST","MATERIAL_UPLOAD","MATERIAL_REMOVE"->"本人";default->"办理人员";};
        String comment=switch(action) {case "EXPIRE","ACCEPT_CANCEL","CANCEL"->"预留已释放，原住宿保留。";case "CHECK_IN"->"新住宿已生效，原床位已释放。";case "KEEP"->"取消未获同意，请按当前预留继续办理。";case "MATERIAL_UPLOAD","MATERIAL_REMOVE"->"仅更新当前材料清单，已提交的历史版本保留。";default->"";};
        return new Event(source+"-"+id,action,HOUSING_TITLES.getOrDefault(action,"调宿办理记录"),actor,comment,null,null,created);
    }
    private String title(String kind,String action,String previousStatus) {
        if("RETURNED".equals(previousStatus) && List.of("SUBMITTED","submit").contains(action))return "补交后重新提交";
        if(kind.equals("RESERVATION"))return switch(action) {
            case "SAVE"->"保存借用草稿";case "SUBMIT"->"提交借用申请";case "WITHDRAW"->"撤回借用申请";case "APPROVE"->"审批通过借用";case "RETURN"->"退回补充说明";case "REJECT"->"未获审批通过";case "CANCEL"->"取消场地借用";case "RESOURCE_DISABLED"->"场地停用取消预约";case "START"->"登记开始使用";case "COMPLETE"->"确认使用结束";case "EXPIRE"->"预约过期未使用";default->"借用办理记录";
        };
        if(kind.equals("REPAIR"))return switch(action) {
            case "SAVE"->"保存报修资料";case "UPLOAD"->"上传报修图片";case "REMOVE_IMAGE"->"移出当前图片";case "submit"->"提交报修";case "withdraw"->"撤回报修";
            case "return"->"后勤退回补充";case "reject"->"后勤拒绝报修";case "assign"->"派给维修人员";case "reassign"->"改派维修人员";case "accept"->"维修人员接单";
            case "progress"->"记录维修进展";case "unable"->"交回后勤处理";case "complete"->"提交维修结果";case "confirm"->"本人确认完成";case "reopen"->"反馈问题未解决";
            case "evaluation"->"提交服务评价";case "priority"->"调整处理优先级";case "CANCEL_REQUEST"->"申请取消报修";case "CANCEL_WITHDRAW"->"撤销取消申请";case "CANCEL_APPROVE"->"后勤同意取消";case "CANCEL_REJECT"->"后勤未同意取消";default->"报修办理记录";
        };
        return switch(action) {case "RETURN_REPORTED"->"学生申报返校";case "RETURN_CONFIRMED"->"辅导员确认销假";case "RETURN_DECLINED"->"返校申报退回核对";case "CREATED"->"保存申请草稿";case "UPDATED"->"更新申请资料";case "FILE"->"补充申请材料";case "SUBMITTED"->"提交审核";case "RETURNED"->"退回补充资料";case "APPROVED"->"请假审核通过";case "COMPLETED"->"生成演示证明";case "REJECTED"->"申请未通过";case "WITHDRAWN"->"本人撤回申请";default->"申请办理记录";};
    }
    private String nextStep(String kind,Current current) {
        if(current.restriction()!=null && !current.restriction().isBlank())return current.restriction();
        if(current.cancellationPending() && List.of("AWAITING_CHECKIN","ASSIGNED","IN_PROGRESS","AWAITING_CONFIRMATION").contains(current.status()))return kind.equals("HOUSING")?"取消请求已提交，请等待宿管处理，并留意当前入住期限。":"取消申请待后勤处理，维修和验收暂时暂停。";
        return switch(current.status()) {
            case "DRAFT"->"请核对资料，在原申请中手动提交。";
            case "RETURNED"->"请查看退回意见，补充资料后在原申请重新提交。";
            case "SUBMITTED"->kind.equals("REPAIR")?"等待后勤分诊，请留意个人消息。":kind.equals("HOUSING")?"等待所属学院初审，请留意补交要求。":kind.equals("RESERVATION")?"申请已提交，等待学生事务部门审核场地时段。":"等待负责人员审核，请留意个人消息。";
            case "ASSIGNED"->"等待维修人员接单。";case "IN_PROGRESS"->"维修正在处理中，请留意进展。";
            case "AWAITING_CONFIRMATION"->"请核对维修结果，在原工单确认修复或反馈问题。";
            case "AWAITING_ASSIGNMENT"->"学院初审已通过，等待宿管安排床位。";
            case "AWAITING_CHECKIN"->"请在原申请核对床位和入住期限，联系宿管确认入住。";
            case "APPROVED"->kind.equals("RESERVATION")?"场地借用审批已通过，请准时入场并出示借用记录。":"请假已批准，返校后请在原申请申报销假。";
            case "RETURN_PENDING"->"返校已申报，等待辅导员确认销假。";
            case "IN_USE"->"场地使用中，结束后请联系负责人登记核销。";
            case "COMPLETED"->kind.equals("LEAVE")?"辅导员已确认返校，请假已销假结束。":kind.equals("RESERVATION")?"使用已结束核销，原预约与办理记录保留。":kind.equals("CERTIFICATE")?"证明已生成，请在原申请下载演示证明。":kind.equals("HOUSING")?"调宿已完成，可在我的住宿核对新床位。":"报修已完成，可在原工单查看结果及评价。";
            case "EXPIRED"->kind.equals("RESERVATION")?"批准时段已结束且未登记使用，预约已过期；需要使用请重新申请。":"预留已到期，原住宿保留；如仍需调宿，请重新申请。";
            case "REJECTED","WITHDRAWN","CANCELLED"->"本次办理已结束，历史保留；重新办理请新建申请。";
            default->"请在原申请核对当前状态与可用操作。";
        };
    }
    private ApiException invalid() {return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_AFFAIR_TIMELINE","办事种类、编号或分页参数无效");}
}
