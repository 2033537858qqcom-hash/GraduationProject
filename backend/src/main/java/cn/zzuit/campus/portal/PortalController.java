package cn.zzuit.campus.portal;

import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final TaskAggregationService taskAggregator;

    public PortalController(
        JdbcClient jdbc,
        IdentityService identities,
        cn.zzuit.campus.leave.LeaveService leaves,
        cn.zzuit.campus.certificate.CertificateService certificates,
        cn.zzuit.campus.notice.NoticeService messages,
        TaskAggregationService taskAggregator
    ) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.leaves = leaves;
        this.certificates = certificates;
        this.messages = messages;
        this.taskAggregator = taskAggregator;
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
        var profile = identities.profile(user.id());
        var permissions = identities.permissions(user.id());
        var taskSummary = taskAggregator.aggregate(user);
        var workspaces = taskAggregator.resolveWorkspaces(profile, permissions);

        return Map.of(
            "profile", profile,
            "workspaces", workspaces,
            "tasks", taskSummary.tasks().stream().limit(8).toList(),
            "totalPending", taskSummary.totalPending(),
            "taskCounts", taskSummary.domainCounts(),
            "leavePending", taskSummary.domainCounts().getOrDefault("leave", 0L),
            "certificatePending", taskSummary.domainCounts().getOrDefault("certificate", 0L),
            "complete", taskSummary.complete(),
            "sourceErrors", taskSummary.sourceErrors(),
            "message", !taskSummary.complete() ? "部分待办暂时无法读取，请重试；当前数量仅包含已读取业务。" : taskSummary.totalPending() > 0
                ? "当前共有 " + taskSummary.totalPending() + " 项业务待办需要处理，请及时审批办理。"
                : "当前没有未处理的待办业务，可从下方进入对应工作空间与服务目录。"
        );
    }

    @GetMapping("/api/workbench/tasks")
    TaskAggregationService.TaskPage tasks(
        @AuthenticationPrincipal CampusPrincipal user,
        @RequestParam(required = false) String domain,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "8") int pageSize
    ) {
        return taskAggregator.page(user, domain, page, pageSize);
    }

    public record Lesson(long id, String courseName, String teacherName, String classroom,
                         String campusName, LocalDateTime startsAt, LocalDateTime endsAt) {}
    public record Overview(IdentityService.Profile profile, List<Lesson> timetable, List<cn.zzuit.campus.notice.NoticeService.Notice> notices,long unreadCount) {}
}
