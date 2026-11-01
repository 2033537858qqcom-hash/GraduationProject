package cn.zzuit.campus.foundation;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Counts only. Application contents remain behind their original domain permissions. */
@Service
public class ResponsibilityService {
    public record Group(String code,String name,long scopeCount,long pendingCount,String path,String permission) {}
    public record Impact(long userId,int version,long blockingCount,List<Group> groups) {}
    public record ExceptionGroup(String code,String name,long count,String unit,String path,String permission) {}
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private static final String VENUE_OPEN="(r.status='SUBMITTED' OR (r.status='APPROVED' AND r.ends_at>CURRENT_TIMESTAMP) OR r.status='IN_USE')";
    private static final String REPAIR_MATCH="s.enabled=TRUE AND s.campus_id=r.campus_id AND s.category=r.category AND (s.location_id IS NULL OR s.location_id=r.location_id)";
    private static final String REVIEW_OPEN="r.status IN ('SUBMITTED','RETURNED')";
    public ResponsibilityService(JdbcClient jdbc,IdentityService identities) { this.jdbc=jdbc;this.identities=identities; }
    private static String permission(String user,String permission) {
        return "EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code WHERE ur.user_id="+user+" AND rp.permission_code='"+permission+"')";
    }
    public Impact preview(CampusPrincipal actor,long id) {
        identities.requirePermission(actor,"foundation:manage");
        int version=jdbc.sql("SELECT version FROM app_user WHERE id=:u").param("u",id).query(Integer.class).optional()
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","账号不存在"));
        var groups=groups(id);
        long blocking=groups.stream().mapToLong(g->g.scopeCount()+(g.code().equals("ACADEMY") || g.code().equals("CONTENT") || g.code().equals("REPAIR_WORK") || g.code().startsWith("FINANCE")?g.pendingCount():0)).sum();
        return new Impact(id,version,blocking,groups);
    }
    private long count(String sql,long id) { return jdbc.sql(sql).param("u",id).query(Long.class).single(); }
    private List<Group> groups(long id) {
        var result=new ArrayList<Group>();
        result.add(new Group("COUNSELOR","行政班与请假",count("SELECT COUNT(*) FROM counselor_assignment WHERE counselor_id=:u",id),
            count("SELECT COUNT(*) FROM leave_request r JOIN counselor_assignment a ON a.class_group_id=r.class_group_id WHERE a.counselor_id=:u AND r.status IN ('SUBMITTED','RETURNED','APPROVED','RETURN_PENDING')",id),"/admin/counselors","foundation:manage"));
        result.add(new Group("VENUE","场地与设备办理",count("SELECT COUNT(*) FROM venue_manager WHERE user_id=:u",id),
            count("SELECT COUNT(*) FROM venue_reservation r WHERE "+VENUE_OPEN+" AND EXISTS(SELECT 1 FROM venue_manager vm WHERE vm.venue_id=r.venue_id AND vm.user_id=:u)",id),"/student-affairs/reservations?tab=manage","reservation:manage"));
        result.add(new Group("REPAIR_DISPATCH","报修派工范围",count("SELECT COUNT(*) FROM repair_staff_scope WHERE user_id=:u AND enabled=TRUE AND capability='DISPATCH'",id),
            count("SELECT COUNT(*) FROM repair_request r WHERE r.status IN ('SUBMITTED','ASSIGNED','IN_PROGRESS') AND r.reporter_id<>:u AND EXISTS(SELECT 1 FROM repair_staff_scope s WHERE s.user_id=:u AND s.capability='DISPATCH' AND "+REPAIR_MATCH+")",id),"/admin/repair-settings","repair:configure"));
        result.add(new Group("REPAIR_WORK","维修范围与在办任务",count("SELECT COUNT(*) FROM repair_staff_scope WHERE user_id=:u AND enabled=TRUE AND capability='WORK'",id),
            count("SELECT COUNT(*) FROM repair_request WHERE technician_id=:u AND status IN ('ASSIGNED','IN_PROGRESS')",id),"/admin/repair-settings","repair:configure"));
        result.add(new Group("HOUSING","宿舍楼栋与调宿办理",count("SELECT COUNT(*) FROM dorm_manager_scope WHERE user_id=:u AND enabled=TRUE",id),
            count("SELECT COUNT(*) FROM housing_transfer t WHERE (t.status='AWAITING_CHECKIN' AND EXISTS(SELECT 1 FROM dorm_manager_scope s WHERE s.user_id=:u AND s.enabled=TRUE AND s.building_id=t.target_building_id)) OR (t.status='AWAITING_ASSIGNMENT' AND EXISTS(SELECT 1 FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id JOIN dorm_building managed ON managed.campus_id=b.campus_id JOIN dorm_manager_scope s ON s.building_id=managed.id WHERE d.id=t.source_bed_id AND s.user_id=:u AND s.enabled=TRUE AND managed.enabled=TRUE))",id),"/admin/housing","housing:configure"));
        long academy=count("SELECT COUNT(*) FROM certificate_request r JOIN app_user target ON target.id=:u AND target.department_id=r.department_id WHERE "+REVIEW_OPEN+" AND r.student_id<>:u AND "+permission("target.id","certificate:review")+" AND NOT EXISTS(SELECT 1 FROM app_user other WHERE other.enabled=TRUE AND other.department_id=r.department_id AND other.id<>:u AND other.id<>r.student_id AND "+permission("other.id","certificate:review")+")",id);
        academy+=count("SELECT COUNT(*) FROM housing_transfer r JOIN app_user target ON target.id=:u AND target.department_id=r.department_id WHERE r.status='SUBMITTED' AND r.student_id<>:u AND "+permission("target.id","housing:review")+" AND NOT EXISTS(SELECT 1 FROM app_user other WHERE other.enabled=TRUE AND other.department_id=r.department_id AND other.id<>:u AND other.id<>r.student_id AND "+permission("other.id","housing:review")+")",id);
        result.add(new Group("ACADEMY","学院审核的独有待办",0,academy,"/admin/foundation","foundation:manage"));
        long content=count("SELECT COUNT(*) FROM website_content c JOIN app_user target ON target.id=:u WHERE c.workflow_status='IN_REVIEW' AND c.created_by<>:u AND "+permission("target.id","content:review")+" AND NOT EXISTS(SELECT 1 FROM app_user other WHERE other.enabled=TRUE AND other.id<>:u AND other.id<>c.created_by AND "+permission("other.id","content:review")+")",id);
        result.add(new Group("CONTENT","官网内容审核的独有待办",0,content,"/admin/content","foundation:manage"));
        long financeWork=0;
        if(identities.permissions(id).contains("payment:manage") && count("SELECT COUNT(*) FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>:u AND "+permission("u.id","payment:manage"),id)==0)
            financeWork=count("SELECT (SELECT COUNT(*) FROM payment_bill WHERE workflow_status IN ('DRAFT','RETURNED','PENDING_REVIEW','PUBLISHED') AND (status='UNPAID' OR pending_request_id IS NOT NULL))+(SELECT COUNT(*) FROM payment_basis WHERE status='SUBMITTED')+(SELECT COUNT(*) FROM payment_request WHERE status IN ('FAILED','PROCESSING'))+(SELECT COUNT(*) FROM payment_reconciliation_case WHERE status<>'CLOSED')",id);
        result.add(new Group("FINANCE_OPERATOR","财务经办统一队列的接任保护",0,financeWork,"/admin/foundation","foundation:manage"));
        long financeReview=0;
        if(identities.permissions(id).contains("payment:review")) {
            financeReview=count("SELECT COUNT(*) FROM payment_bill b WHERE b.workflow_status='PENDING_REVIEW' AND b.created_by<>:u AND NOT EXISTS(SELECT 1 FROM payment_history h WHERE h.entity_type='BILL' AND h.entity_id=b.id AND h.actor_id=:u AND h.action IN ('CREATE','UPDATE')) AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>:u AND u.id<>b.created_by AND "+permission("u.id","payment:review")+" AND NOT EXISTS(SELECT 1 FROM payment_history h WHERE h.entity_type='BILL' AND h.entity_id=b.id AND h.actor_id=u.id AND h.action IN ('CREATE','UPDATE')))",id);
            financeReview+=count("SELECT COUNT(*) FROM payment_standard s WHERE s.status='PENDING_REVIEW' AND s.created_by<>:u AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>:u AND u.id<>s.created_by AND "+permission("u.id","payment:review")+")",id);
            financeReview+=count("SELECT COUNT(*) FROM payment_request r WHERE r.status='SUBMITTED' AND r.requested_by<>:u AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>:u AND u.id<>r.requested_by AND "+permission("u.id","payment:review")+")",id);
            financeReview+=count("SELECT COUNT(*) FROM payment_reconciliation_case c WHERE c.status='PENDING_REVIEW' AND c.assignee_id<>:u AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>:u AND u.id<>c.assignee_id AND "+permission("u.id","payment:review")+")",id);
        }
        result.add(new Group("FINANCE_REVIEWER","财务复核的独有待办",0,financeReview,"/admin/foundation","foundation:manage"));
        return result;
    }
    public void requireNormalDisable(CampusPrincipal actor,long id) {
        if(preview(actor,id).blockingCount()>0) conflict();
    }
    public void requireRoleHandoff(long id,List<String> roles,boolean departmentChanged) {
        var permissions=jdbc.sql("SELECT DISTINCT permission_code FROM role_permission WHERE role_code IN (:roles)").param("roles",roles).query(String.class).list();
        for(var group:groups(id)) {
            String required=switch(group.code()) {case "VENUE"->"reservation:review";case "REPAIR_DISPATCH"->"repair:dispatch";case "REPAIR_WORK"->"repair:work";case "HOUSING"->"housing:assign";case "FINANCE_OPERATOR"->"payment:manage";case "FINANCE_REVIEWER"->"payment:review";default->null;};
            if(required!=null && !permissions.contains(required) && (group.scopeCount()>0 || group.pendingCount()>0)) conflict();
            if(group.code().equals("ACADEMY") && group.pendingCount()>0 && (departmentChanged || !permissions.contains("certificate:review") || !permissions.contains("housing:review"))) conflict();
            if(group.code().equals("CONTENT") && group.pendingCount()>0 && !permissions.contains("content:review")) conflict();
        }
    }
    private void conflict() { throw new ApiException(HttpStatus.CONFLICT,"HANDOFF_REQUIRED","仍有负责范围或独有在办事项，请先完成岗位交接；紧急撤权可使用紧急停用并在责任异常中安排接任"); }
    private ExceptionGroup exception(String code,String name,String sql,String unit,String path,String permission) {
        return new ExceptionGroup(code,name,jdbc.sql(sql).query(Long.class).single(),unit,path,permission);
    }
    public List<ExceptionGroup> exceptions(CampusPrincipal actor) {
        identities.requirePermission(actor,"foundation:manage");
        String venueOwner="EXISTS(SELECT 1 FROM venue_manager vm JOIN app_user u ON u.id=vm.user_id AND u.enabled=TRUE WHERE vm.venue_id=v.id AND "+permission("u.id","reservation:review")+")";
        String venueTaskOwner="EXISTS(SELECT 1 FROM venue_manager vm JOIN app_user u ON u.id=vm.user_id AND u.enabled=TRUE WHERE vm.venue_id=r.venue_id AND u.id<>r.applicant_id AND "+permission("u.id","reservation:review")+")";
        String repairOwner="EXISTS(SELECT 1 FROM repair_staff_scope s JOIN app_user u ON u.id=s.user_id AND u.enabled=TRUE WHERE "+REPAIR_MATCH+" AND u.id<>r.reporter_id AND ";
        String counselorOwner="EXISTS(SELECT 1 FROM counselor_assignment a JOIN app_user u ON u.id=a.counselor_id AND u.enabled=TRUE JOIN class_group cg ON cg.id=a.class_group_id JOIN major m ON m.id=cg.major_id AND m.department_id=u.department_id WHERE a.class_group_id=r.class_group_id AND "+permission("u.id","leave:review")+" AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='TEACHER'))";
        String housingOwner="EXISTS(SELECT 1 FROM dorm_manager_scope s JOIN dorm_building b ON b.id=s.building_id AND b.enabled=TRUE JOIN app_user u ON u.id=s.user_id AND u.enabled=TRUE WHERE s.enabled=TRUE AND "+permission("u.id","housing:assign")+" AND ((t.status='AWAITING_CHECKIN' AND s.building_id=t.target_building_id) OR (t.status='AWAITING_ASSIGNMENT' AND EXISTS(SELECT 1 FROM dorm_bed d JOIN dorm_room dr ON dr.id=d.room_id JOIN dorm_building source ON source.id=dr.building_id WHERE d.id=t.source_bed_id AND source.campus_id=b.campus_id))))";
        return List.of(
            exception("VENUE","开放资源缺少有效办理人员","SELECT COUNT(*) FROM venue_resource v WHERE v.enabled=TRUE AND NOT "+venueOwner,"项资源","/student-affairs/reservations?tab=manage","reservation:manage"),
            exception("VENUE_WORK","未结束预约无人办理","SELECT COUNT(*) FROM venue_reservation r WHERE "+VENUE_OPEN+" AND NOT "+venueTaskOwner,"条事项","/student-affairs/reservations?tab=manage","reservation:manage"),
            exception("COUNSELOR","未完成请假缺少有效辅导员","SELECT COUNT(*) FROM leave_request r WHERE r.status IN ('SUBMITTED','RETURNED','APPROVED','RETURN_PENDING') AND NOT "+counselorOwner,"条事项","/admin/counselors","foundation:manage"),
            exception("CERTIFICATE","证明申请缺少学院审核人员","SELECT COUNT(*) FROM certificate_request r WHERE "+REVIEW_OPEN+" AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.department_id=r.department_id AND u.id<>r.student_id AND "+permission("u.id","certificate:review")+")","条事项","/admin/foundation","foundation:manage"),
            exception("HOUSING_REVIEW","调宿申请缺少学院初审人员","SELECT COUNT(*) FROM housing_transfer r WHERE r.status='SUBMITTED' AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.department_id=r.department_id AND u.id<>r.student_id AND "+permission("u.id","housing:review")+")","条事项","/admin/foundation","foundation:manage"),
            exception("CONTENT","官网待审内容缺少审核人员","SELECT COUNT(*) FROM website_content c WHERE c.workflow_status='IN_REVIEW' AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND (c.created_by IS NULL OR u.id<>c.created_by) AND "+permission("u.id","content:review")+")","条内容","/admin/content","foundation:manage"),
            exception("REPAIR_DISPATCH","在办报修缺少有效派工人员","SELECT COUNT(*) FROM repair_request r WHERE r.status IN ('SUBMITTED','ASSIGNED','IN_PROGRESS') AND NOT "+repairOwner+"s.capability='DISPATCH' AND "+permission("u.id","repair:dispatch")+")","条事项","/admin/repair-settings","repair:configure"),
            exception("REPAIR_WORK","已派维修任务的负责人失效","SELECT COUNT(*) FROM repair_request r WHERE r.status IN ('ASSIGNED','IN_PROGRESS') AND NOT "+repairOwner+"s.capability='WORK' AND s.user_id=r.technician_id AND "+permission("u.id","repair:work")+")","条事项","/admin/repair-settings","repair:configure"),
            exception("HOUSING","调宿分配或入住确认无人办理","SELECT COUNT(*) FROM housing_transfer t WHERE t.status IN ('AWAITING_ASSIGNMENT','AWAITING_CHECKIN') AND NOT "+housingOwner,"条事项","/admin/housing","housing:configure"),
            exception("FINANCE_WORK","财务收费队列缺少有效经办人员","SELECT COUNT(*) FROM payment_bill b WHERE b.workflow_status IN ('DRAFT','RETURNED','PENDING_REVIEW','PUBLISHED') AND (b.status='UNPAID' OR b.pending_request_id IS NOT NULL) AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND "+permission("u.id","payment:manage")+")","条账单","/admin/foundation","foundation:manage"),
            exception("FINANCE_REVIEW","收费账单缺少独立复核人员","SELECT COUNT(*) FROM payment_bill b WHERE b.workflow_status='PENDING_REVIEW' AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>b.created_by AND "+permission("u.id","payment:review")+" AND NOT EXISTS(SELECT 1 FROM payment_history h WHERE h.entity_type='BILL' AND h.entity_id=b.id AND h.actor_id=u.id AND h.action IN ('CREATE','UPDATE')))","条账单","/admin/foundation","foundation:manage"),
            exception("FINANCE_REQUEST","财务申请缺少独立复核人员","SELECT COUNT(*) FROM payment_request r WHERE r.status='SUBMITTED' AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>r.requested_by AND "+permission("u.id","payment:review")+")","条申请","/admin/foundation","foundation:manage")
            ,exception("RECON_WORK","对账事项缺少财务经办人员","SELECT COUNT(*) FROM payment_reconciliation_case c WHERE c.status IN ('OPEN','IN_PROGRESS') AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND "+permission("u.id","payment:manage")+")","条事项","/admin/foundation","foundation:manage")
            ,exception("RECON_REVIEW","对账事项缺少独立复核人员","SELECT COUNT(*) FROM payment_reconciliation_case c WHERE c.status='PENDING_REVIEW' AND NOT EXISTS(SELECT 1 FROM app_user u WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>c.assignee_id AND "+permission("u.id","payment:review")+")","条事项","/admin/foundation","foundation:manage")
        );
    }
}
