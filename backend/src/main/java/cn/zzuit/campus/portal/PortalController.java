package cn.zzuit.campus.portal;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PortalController {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final cn.zzuit.campus.leave.LeaveService leaves;
    private final cn.zzuit.campus.certificate.CertificateService certificates;
    private final cn.zzuit.campus.notice.NoticeService messages;
    public PortalController(JdbcClient jdbc, IdentityService identities,cn.zzuit.campus.leave.LeaveService leaves,
        cn.zzuit.campus.certificate.CertificateService certificates,cn.zzuit.campus.notice.NoticeService messages) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.leaves = leaves;
        this.certificates = certificates;
        this.messages = messages;
    }

    @GetMapping("/api/portal/overview")
    Overview overview(@AuthenticationPrincipal CampusPrincipal user) {
        var timetable = jdbc.sql("""
            SELECT l.id,co.name course_name,u.full_name teacher_name,l.classroom,l.starts_at,l.ends_at,c.name campus_name
            FROM teaching_lesson l
            JOIN teaching_class tc ON tc.id=l.class_id JOIN course co ON co.id=tc.course_id
            JOIN app_user u ON u.id=l.teacher_id JOIN campus c ON c.id=l.campus_id
            JOIN teaching_member m ON m.class_id=tc.id AND m.student_id=:user AND m.active=TRUE
            JOIN teaching_instructor ti ON ti.class_id=tc.id AND ti.teacher_id=l.teacher_id AND ti.active=TRUE
            WHERE l.starts_at>=:now ORDER BY l.starts_at LIMIT 6
            """).param("user", user.id()).param("now",java.sql.Timestamp.valueOf(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))).query((rs, row) -> new Lesson(rs.getLong("id"),
                rs.getString("course_name"), rs.getString("teacher_name"), rs.getString("classroom"),
                rs.getString("campus_name"), rs.getTimestamp("starts_at").toLocalDateTime(),
                rs.getTimestamp("ends_at").toLocalDateTime())).list();
        var page=messages.list(user,null,1,6);
        return new Overview(identities.profile(user.id()), timetable, page.items(),page.unreadCount());
    }

    @PostMapping("/api/portal/notices/{id}/read")
    Map<String, Boolean> read(@PathVariable long id, @AuthenticationPrincipal CampusPrincipal user) {
        messages.read(user,id);
        return Map.of("read", true);
    }

    @GetMapping("/api/workbench/overview")
    Map<String, Object> workbench(@AuthenticationPrincipal CampusPrincipal user) {
        return Map.of("profile", identities.profile(user.id()), "tasks", List.of(),
            "leavePending",identities.permissions(user.id()).contains("leave:review") ? leaves.list(user,"review","SUBMITTED",1,1).total() : 0,
            "certificatePending",identities.permissions(user.id()).contains("certificate:review") ? certificates.list(user,"review","SUBMITTED",1,1).total() : 0,
            "message", identities.permissions(user.id()).contains("foundation:manage") ? "可维护校园账号、组织目录和岗位分配，查询修改记录。"
                : identities.permissions(user.id()).contains("leave:review") ? "可进入班级请假处理，核对所负责行政班的当前待办。"
                : identities.permissions(user.id()).contains("certificate:review") ? "可进入学院证明申请处理，核对所属学院资料并生成演示证明。"
                : identities.permissions(user.id()).contains("teaching:manage") ? "可进入我的教学，查看授课安排并维护本人教学班资料与通知。"
                : identities.permissions(user.id()).contains("academic:manage") ? "可进入教务信息维护，管理本学院教学关系并发布学习信息。"
                : "当前已建立身份与岗位入口，业务处理任务将在对应模块接入。");
    }

    public record Lesson(long id, String courseName, String teacherName, String classroom,
                         String campusName, LocalDateTime startsAt, LocalDateTime endsAt) {}
    public record Overview(IdentityService.Profile profile, List<Lesson> timetable, List<cn.zzuit.campus.notice.NoticeService.Notice> notices,long unreadCount) {}
}
