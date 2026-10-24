package cn.zzuit.campus.portal;

import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.housing.HousingTransferService;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveService;
import cn.zzuit.campus.repair.RepairService;
import cn.zzuit.campus.reservation.VenueReservationService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TaskAggregationService {
    private static final Logger log = LoggerFactory.getLogger(TaskAggregationService.class);
    private static final int SOURCE_PAGE_SIZE = 100;

    public record AggregatedTask(String id, String domain, String categoryName, String title,
        String status, String statusText, String applicantName, String scopeDetail,
        String targetPath, LocalDateTime createdAt, String urgency) {}
    public record TaskSourceError(String domain, String label, String message) {}
    public record TaskSummary(long totalPending, Map<String, Long> domainCounts,
        List<AggregatedTask> tasks, boolean complete, List<TaskSourceError> sourceErrors) {}
    public record TaskPage(long totalPending, Map<String, Long> domainCounts,
        List<AggregatedTask> tasks, long total, int page, int pageSize,
        boolean complete, List<TaskSourceError> sourceErrors) {}
    public record WorkspaceInfo(String code, String name, String roleTitle, String scopeNote, List<String> entryPaths) {}
    private record SourcePage<T>(List<T> items, long total) {}

    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final LeaveService leaves;
    private final CertificateService certificates;
    private final VenueReservationService reservations;
    private final HousingTransferService housingTransfer;
    private final RepairService repairs;

    public TaskAggregationService(JdbcClient jdbc, IdentityService identities, LeaveService leaves,
        CertificateService certificates, VenueReservationService reservations,
        HousingTransferService housingTransfer, RepairService repairs) {
        this.jdbc=jdbc; this.identities=identities; this.leaves=leaves; this.certificates=certificates;
        this.reservations=reservations; this.housingTransfer=housingTransfer; this.repairs=repairs;
    }

    public TaskSummary aggregate(CampusPrincipal user) { return aggregate(user, null); }

    public TaskSummary aggregate(CampusPrincipal user, String filterDomain) {
        var domains = domains(filterDomain);
        var permissions = identities.permissions(user.id());
        var counts = new LinkedHashMap<String, Long>();
        var tasks = new ArrayList<AggregatedTask>();
        var errors = new ArrayList<TaskSourceError>();

        source(user, permissions, "leave:review", "leave", "班级请假", counts, tasks, errors, () -> {
            var items=java.util.stream.Stream.of("SUBMITTED","RETURN_PENDING","APPROVED")
                .flatMap(state->readAll(p -> { var page=leaves.list(user,"review",state,p,SOURCE_PAGE_SIZE); return new SourcePage<>(page.items(),page.total()); }).stream())
                .filter(item->!item.status().equals("APPROVED") || item.endsAt().isBefore(LocalDateTime.now())).toList();
            return items.stream().map(item -> new AggregatedTask("LEAVE-"+item.id(),"LEAVE","班级请假",
                item.studentName()+" - "+leaveReasonTypeText(item.reasonType())+"（"+truncate(item.reason(),30)+"）",
                item.status(),item.status().equals("RETURN_PENDING")?"待确认销假":item.status().equals("APPROVED")?"请假已到期，待返校跟进":"待辅导员审核",item.studentName(),item.className(),"/student-affairs/leave?id="+item.id()+"&status="+item.status(),
                item.createdAt(),urgent(item.startsAt(),24))).toList();
        });
        source(user, permissions, "certificate:review", "certificate", "学院证明", counts, tasks, errors, () -> {
            var items=readAll(p -> { var page=certificates.list(user,"review","SUBMITTED",p,SOURCE_PAGE_SIZE); return new SourcePage<>(page.items(),page.total()); });
            return items.stream().map(item -> new AggregatedTask("CERT-"+item.id(),"CERTIFICATE","学院证明",
                item.identity().studentName()+" - "+item.purpose(),item.status(),"待学院初审",
                item.identity().studentName(),item.identity().departmentName(),"/student-affairs/certificates",item.createdAt(),"NORMAL")).toList();
        });
        source(user, permissions, "reservation:review", "reservation", "场地借用", counts, tasks, errors, () -> {
            var items=java.util.stream.Stream.of("SUBMITTED","APPROVED","IN_USE")
                .flatMap(state->readAll(p -> { var page=reservations.listReview(user,state,null,p,SOURCE_PAGE_SIZE); return new SourcePage<>(page.items(),page.total()); }).stream())
                .filter(item->!item.status().equals("APPROVED") || !item.startsAt().isAfter(LocalDateTime.now())).toList();
            return items.stream().filter(item -> item.applicantId()!=user.id()).map(item -> new AggregatedTask(
                "RES-"+item.id(),"RESERVATION","场地借用",item.applicantName()+" - "+item.venueName()+"（"+truncate(item.activityTheme(),25)+"）",
                item.status(),item.status().equals("IN_USE")?"待使用结束核销":item.status().equals("APPROVED")?"待登记开始使用":"待场地负责人审批",item.applicantName(),item.venueName(),"/student-affairs/reservations?id="+item.id()+"&status="+item.status(),
                item.createdAt(),urgent(item.startsAt(),48))).toList();
        });
        source(user, permissions, "housing:review", "housingReview", "调宿初审", counts, tasks, errors, () ->
            housingTransfer.review(user,"SUBMITTED").stream().map(item -> new AggregatedTask(
                "HOUSING-REV-"+item.id(),"HOUSING_REVIEW","调宿初审",item.studentName()+" - 申请调宿（"+truncate(item.reason(),30)+"）",
                item.status(),"待学院初审",item.studentName(),item.sourcePlace()+" → 期望区域: "+item.preferredArea(),
                "/student-affairs/housing",item.updatedAt(),"NORMAL")).toList());
        source(user, permissions, "housing:assign", "housingAssign", "床位分配", counts, tasks, errors, () ->
            housingTransfer.assignQueue(user,"waiting").stream().map(item -> new AggregatedTask(
                "HOUSING-ASSIGN-"+item.id(),"HOUSING_ASSIGN","床位分配",item.studentName()+" - 调宿待安排床位",
                item.status(),"待宿管排床",item.studentName(),"原住宿: "+item.sourcePlace()+" / 期望: "+item.preferredArea(),
                "/housing/assignments",item.updatedAt(),"NORMAL")).toList());
        source(user, permissions, "repair:dispatch", "repairDispatch", "报修分诊", counts, tasks, errors, () -> {
            var items=readAll(p -> { var page=repairs.list(user,"dispatch","SUBMITTED",p,SOURCE_PAGE_SIZE); return new SourcePage<>(page.items(),page.total()); });
            return items.stream().map(item -> new AggregatedTask("REPAIR-DISP-"+item.id(),"REPAIR_DISPATCH","报修分诊",
                item.title()+" · "+repairCategoryText(item.category()),item.status(),item.cancellationPending()?"取消待处理":"待分诊派单",
                item.reporterName(),item.campusName()+" · "+item.locationName(),"/logistics/repairs/dispatch",item.updatedAt(),
                "HIGH".equalsIgnoreCase(item.priority())?"URGENT":"NORMAL")).toList();
        });
        source(user, permissions, "repair:work", "repairWork", "维修任务", counts, tasks, errors, () -> {
            var items=readAll(p -> { var page=repairs.list(user,"work","ASSIGNED",p,SOURCE_PAGE_SIZE); return new SourcePage<>(page.items(),page.total()); });
            items.addAll(readAll(p -> { var page=repairs.list(user,"work","IN_PROGRESS",p,SOURCE_PAGE_SIZE); return new SourcePage<>(page.items(),page.total()); }));
            return items.stream().map(item -> new AggregatedTask("REPAIR-WORK-"+item.id(),"REPAIR_WORK","维修任务",
                item.title()+" · "+repairCategoryText(item.category()),item.status(),"ASSIGNED".equals(item.status())?"待接单":"维修处理中",
                item.reporterName(),item.campusName()+" · "+item.locationName(),"/logistics/repairs/tasks",item.updatedAt(),
                "HIGH".equalsIgnoreCase(item.priority())?"URGENT":"NORMAL")).toList();
        });
        source(user, permissions, "content:review", "content", "官网审核", counts, tasks, errors, () -> jdbc.sql("""
            SELECT c.id,c.title,c.category,c.updated_at,u.full_name creator_name
            FROM website_content c JOIN app_user u ON u.id=c.created_by
            WHERE c.workflow_status='IN_REVIEW' AND c.created_by<>:user
            ORDER BY c.updated_at DESC,c.id DESC
            """).param("user",user.id()).query((r,n) -> new AggregatedTask("CONTENT-"+r.getLong("id"),"CONTENT","官网审核",
                r.getString("title"),"IN_REVIEW","待审核发布",r.getString("creator_name"),contentCategoryText(r.getString("category")),
                "/admin/content",r.getTimestamp("updated_at").toLocalDateTime(),"NORMAL")).list());

        tasks.sort(Comparator.comparing((AggregatedTask t) -> !"URGENT".equals(t.urgency()))
            .thenComparing(AggregatedTask::createdAt,Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AggregatedTask::id));
        var visible=tasks.stream().filter(t -> domains.isEmpty() || domains.contains(t.domain())).toList();
        return new TaskSummary(counts.values().stream().mapToLong(Long::longValue).sum(),Map.copyOf(counts),visible,
            errors.isEmpty(),List.copyOf(errors));
    }

    public TaskPage page(CampusPrincipal user, String domain, int page, int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGING","分页参数无效");
        var summary=aggregate(user,domain);
        var visible=summary.tasks().stream().skip(((long)page-1)*pageSize).limit(pageSize).toList();
        return new TaskPage(summary.totalPending(),summary.domainCounts(),visible,summary.tasks().size(),page,pageSize,
            summary.complete(),summary.sourceErrors());
    }

    // Read every authorized source page before sorting and slicing the merged queue.
    // An arbitrary first-page cap would hide older tasks and invalidate later pages.
    private <T> ArrayList<T> readAll(IntFunction<SourcePage<T>> reader) {
        var result=new ArrayList<T>();
        for(int page=1;;page++) {
            var next=reader.apply(page);
            result.addAll(next.items());
            if(next.items().isEmpty() || result.size()>=next.total()) return result;
        }
    }

    private void source(CampusPrincipal user, Collection<String> permissions, String permission, String key, String label,
        Map<String,Long> counts,List<AggregatedTask> tasks,List<TaskSourceError> errors,Supplier<List<AggregatedTask>> reader) {
        if(!permissions.contains(permission)) return;
        try {
            var items=reader.get();
            counts.put(key,(long)items.size());
            tasks.addAll(items);
        } catch(RuntimeException failure) {
            if(failure instanceof ApiException api && (api.status()==HttpStatus.UNAUTHORIZED || api.status()==HttpStatus.FORBIDDEN)) throw failure;
            log.warn("Task source unavailable: source={}, userId={}, failureType={}",key,user.id(),failure.getClass().getSimpleName());
            errors.add(new TaskSourceError(key,label,"暂时无法读取，请稍后重试"));
        }
    }

    private String urgent(LocalDateTime startsAt,int hours) {
        return startsAt!=null && startsAt.isBefore(LocalDateTime.now().plusHours(hours))?"URGENT":"NORMAL";
    }

    private Set<String> domains(String input) {
        return switch(input==null?"ALL":input.trim().toUpperCase(Locale.ROOT)) {
            case "","ALL" -> Set.of();
            case "LEAVE" -> Set.of("LEAVE");
            case "CERTIFICATE" -> Set.of("CERTIFICATE");
            case "RESERVATION" -> Set.of("RESERVATION");
            case "HOUSING" -> Set.of("HOUSING_REVIEW","HOUSING_ASSIGN");
            case "HOUSINGREVIEW","HOUSING_REVIEW" -> Set.of("HOUSING_REVIEW");
            case "HOUSINGASSIGN","HOUSING_ASSIGN" -> Set.of("HOUSING_ASSIGN");
            case "REPAIR" -> Set.of("REPAIR_DISPATCH","REPAIR_WORK");
            case "REPAIRDISPATCH","REPAIR_DISPATCH" -> Set.of("REPAIR_DISPATCH");
            case "REPAIRWORK","REPAIR_WORK" -> Set.of("REPAIR_WORK");
            case "CONTENT" -> Set.of("CONTENT");
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TASK_DOMAIN","待办分类无效");
        };
    }

    public List<WorkspaceInfo> resolveWorkspaces(IdentityService.Profile profile, Collection<String> permissions) {
        List<WorkspaceInfo> list = new ArrayList<>();

        // 1. 教学工作空间
        if (permissions.contains("teaching:manage")) {
            list.add(new WorkspaceInfo(
                "TEACHING",
                "教学工作",
                "任课教师",
                "本人授课班级、教学资料维护与每周课表",
                List.of("/teaching", "/teaching/timetable")
            ));
        }

        // 2. 班级事务工作空间 (辅导员)
        if (permissions.contains("leave:review")) {
            List<String> classes = jdbc.sql("""
                SELECT c.name FROM counselor_assignment a
                JOIN class_group c ON c.id = a.class_group_id
                WHERE a.counselor_id = :u
                ORDER BY c.name
                """).param("u", profile.id()).query(String.class).list();
            String scope = classes.isEmpty() ? "尚未绑定负责行政班" : "负责行政班: " + String.join("、", classes);
            list.add(new WorkspaceInfo(
                "COUNSELOR",
                "班级事务",
                "班级辅导员",
                scope,
                List.of("/student-affairs/leave")
            ));
        }

        // 3. 学生事务工作空间 (学院事务专员)
        if (permissions.contains("certificate:review") || permissions.contains("reservation:review") || permissions.contains("housing:review")) {
            String dept = profile.departmentName() != null ? "所属院系: " + profile.departmentName() : "学生事务部";
            list.add(new WorkspaceInfo(
                "AFFAIRS",
                "学生事务",
                "学生事务专员",
                dept + " · 证明核验、场地器材审批与调宿初审",
                List.of("/student-affairs/certificates", "/student-affairs/reservations", "/student-affairs/housing")
            ));
        }

        // 4. 宿舍管理工作空间 (宿管)
        if (permissions.contains("housing:assign") || permissions.contains("housing:configure")) {
            list.add(new WorkspaceInfo(
                "HOUSING",
                "宿舍管理",
                "楼栋宿管人员",
                "负责楼栋床位分配、入住登记与退宿确认",
                List.of("/housing/assignments", "/admin/housing")
            ));
        }

        // 5. 后勤服务工作空间 (报修分诊 / 维修工程)
        if (permissions.contains("repair:dispatch") || permissions.contains("repair:work") || permissions.contains("repair:configure")) {
            String roleTitle = permissions.contains("repair:dispatch") ? "后勤分诊专员" : "维修工程人员";
            list.add(new WorkspaceInfo(
                "LOGISTICS",
                "后勤服务",
                roleTitle,
                "设施故障报修分诊、工单分配与维修执行",
                List.of("/logistics/repairs/dispatch", "/logistics/repairs/tasks", "/logistics/repairs/statistics")
            ));
        }

        // 6. 教务管理工作空间
        if (permissions.contains("academic:manage")) {
            list.add(new WorkspaceInfo(
                "ACADEMIC",
                "教务管理",
                "教务管理员",
                "全院教学关系、课程排课与成绩考试管理",
                List.of("/academic")
            ));
        }

        // 7. 系统管理工作空间
        if (permissions.contains("foundation:manage")) {
            list.add(new WorkspaceInfo(
                "ADMIN",
                "系统管理",
                "平台系统管理员",
                "校园组织目录、账号授权与底座配置维护",
                List.of("/admin/foundation", "/admin/resources", "/admin/counselors")
            ));
        }

        // 8. 个人服务 (所有已登录人员均具备)
        list.add(new WorkspaceInfo(
            "PERSONAL",
            "个人服务",
            profile.fullName(),
            "个人资料、日常办事申请与个人通知",
            List.of("/profile", "/portal/notices", "/repairs/mine")
        ));

        return list;
    }

    private String leaveReasonTypeText(String type) {
        if (type == null) return "事假";
        return switch (type) {
            case "PERSONAL" -> "事假";
            case "SICK" -> "病假";
            case "OTHER" -> "其他请假";
            default -> type;
        };
    }

    private String repairCategoryText(String category) {
        if (category == null) return "其他设施";
        return switch (category) {
            case "WATER" -> "给排水";
            case "ELECTRICITY" -> "电气照明";
            case "NETWORK" -> "网络设备";
            case "FURNITURE" -> "桌椅家具";
            case "DOOR_WINDOW" -> "门窗五金";
            case "EQUIPMENT" -> "电器设备";
            case "OTHER" -> "其他设施";
            default -> category;
        };
    }

    private String contentCategoryText(String category) {
        if (category == null) return "综合发布";
        return switch (category) {
            case "NEWS" -> "校园动态";
            case "NOTICE" -> "综合通知";
            case "ACADEMIC" -> "学术交流";
            case "CULTURE" -> "校园文化";
            case "GUIDE" -> "便民办事";
            default -> category;
        };
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
