package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveService;
import cn.zzuit.campus.housing.HousingService;
import cn.zzuit.campus.housing.HousingTransferService;
import cn.zzuit.campus.housing.HousingTransferModels.Transfer;
import cn.zzuit.campus.notice.NoticeService;
import cn.zzuit.campus.repair.RepairModels.Item;
import cn.zzuit.campus.repair.RepairService;
import cn.zzuit.campus.teaching.TeachingModels.Term;
import cn.zzuit.campus.teaching.TeachingService;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentTools {
    private final IdentityService identities;
    private final AgentGuides guides;
    private final TeachingService teaching;
    private final LeaveService leaves;
    private final CertificateService certificates;
    private final NoticeService notices;
    private final RepairService repairs;
    private final HousingService housing;
    private final HousingTransferService transfers;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final List<String> REPAIR_STATUSES=List.of("ALL","DRAFT","SUBMITTED","RETURNED","ASSIGNED","IN_PROGRESS","AWAITING_CONFIRMATION","COMPLETED","REJECTED","WITHDRAWN","CANCELLED");
    public AgentTools(IdentityService identities,AgentGuides guides,TeachingService teaching,LeaveService leaves,CertificateService certificates,NoticeService notices,RepairService repairs,HousingService housing,HousingTransferService transfers) {
        this.identities=identities; this.guides=guides; this.teaching=teaching; this.leaves=leaves; this.certificates=certificates; this.notices=notices; this.repairs=repairs;this.housing=housing;this.transfers=transfers;
    }
    private static final Map<String,String> TITLES=Map.ofEntries(Map.entry("search_guides","查找办理指南"),Map.entry("my_timetable","查看本人课表"),Map.entry("my_grades","查看本人已发布成绩"),
        Map.entry("my_exams","查看本人已发布考试"),Map.entry("my_affairs","查看本人办事进度"),Map.entry("my_notice_summary","查看本人未读消息"),Map.entry("my_review_pending","查看岗位待办"),
        Map.entry("application_status","查看当前申请"),Map.entry("my_repairs","查看本人报修进度"),Map.entry("repair_dispatch_queue","查看派单范围报修"),
        Map.entry("repair_work_queue","查看当前维修任务"),Map.entry("repair_status","查看当前报修"),Map.entry("my_housing","查看本人住宿"),Map.entry("my_housing_transfers","查看本人调宿进度"),
        Map.entry("housing_transfer_status","查看当前调宿"),Map.entry("campus_navigation","校园导览与路线查询"));
    public List<Capability> capabilities(CampusPrincipal user) {
        identities.requirePermission(user,"agent:query");
        var p=identities.permissions(user.id()); var names=new ArrayList<>(List.of("search_guides","my_notice_summary","campus_navigation"));
        if(p.contains("learning:read") || p.contains("teaching:manage")) names.add("my_timetable");
        if(p.contains("learning:read")) names.addAll(List.of("my_grades","my_exams"));
        if(p.contains("portal:read")) names.add("my_affairs");
        if(p.contains("leave:review") || p.contains("certificate:review")) names.add("my_review_pending");
        if(p.stream().anyMatch(x->List.of("leave:apply","leave:review","certificate:apply","certificate:review").contains(x))) names.add("application_status");
        if(p.contains("repair:apply")) names.add("my_repairs");
        if(p.contains("repair:dispatch")) names.add("repair_dispatch_queue");
        if(p.contains("repair:work")) names.add("repair_work_queue");
        if(p.stream().anyMatch(x->List.of("repair:apply","repair:dispatch","repair:work").contains(x))) names.add("repair_status");
        if(p.contains("housing:read"))names.addAll(List.of("my_housing","my_housing_transfers","housing_transfer_status"));
        return names.stream().map(n->new Capability(n,TITLES.get(n))).toList();
    }
    public List<Map<String,Object>> schemas(CampusPrincipal user) {
        return capabilities(user).stream().map(cap-> {
            Map<String,Object> properties=new LinkedHashMap<>();
            switch(cap.name()) {
                case "search_guides" -> properties.put("query",Map.of("type","string","description","办理事项关键词，1—200 字"));
                case "my_timetable" -> { properties.put("week",Map.of("type","string","description","可选 ISO 日期；查询该日期所在的周，不明确时省略")); properties.put("termId",Map.of("type","integer","minimum",1,"description","可选；只在用户明确选择学期编号时提供")); }
                case "my_grades","my_exams" -> properties.put("termId",Map.of("type","integer","minimum",1,"description","可选；省略时选择覆盖今天的唯一学期"));
                case "my_affairs" -> { properties.put("kind",Map.of("type","string","enum",List.of("ALL","LEAVE","CERTIFICATE"))); properties.put("status",Map.of("type","string","enum",List.of("ALL","DRAFT","SUBMITTED","RETURNED","APPROVED","COMPLETED","REJECTED","WITHDRAWN"))); }
                case "application_status" -> { properties.put("kind",Map.of("type","string","enum",List.of("LEAVE","CERTIFICATE"))); properties.put("id",Map.of("type","integer","minimum",1,"description","只取用户明确给出的申请编号；省略时使用本地页面上下文")); properties.put("view",Map.of("type","string","enum",List.of("MINE","REVIEW"),"description","省略时采用页面上下文或本人视图")); }
                case "my_repairs","repair_dispatch_queue","repair_work_queue" -> properties.put("status",Map.of("type","string","enum",REPAIR_STATUSES,"description","省略时查看该范围的全部可见状态"));
                case "housing_transfer_status" -> properties.put("id",Map.of("type","integer","minimum",1,"description","本人调宿申请编号；省略时使用当前调宿页面上下文"));
                case "repair_status" -> { properties.put("id",Map.of("type","integer","minimum",1,"description","只取用户明确给出的工单编号；省略时使用本地页面上下文")); properties.put("view",Map.of("type","string","enum",List.of("MINE","DISPATCH","WORK"),"description","省略时采用页面上下文")); }
                case "campus_navigation" -> {
                    properties.put("destination",Map.of("type","string","description","目的地名称，如：图书馆、毓秀楼、静逸楼、锦绣餐厅、红体馆等"));
                    properties.put("origin",Map.of("type","string","description","出发地，默认南门"));
                }
                default -> { }
            }
            var parameters=new HashMap<String,Object>(); parameters.put("type","object"); parameters.put("properties",properties); parameters.put("additionalProperties",false);
            parameters.put("required",cap.name().equals("search_guides")?List.of("query"):cap.name().equals("campus_navigation")?List.of("destination"):List.of());
            return Map.<String,Object>of("type","function","function",Map.of("name",cap.name(),"description",cap.title()+"。仅按当前账号权限读取，不能指定学生、账号或任意 URL，也不能修改业务。","parameters",parameters));
        }).toList();
    }
    public void validateContext(CampusPrincipal user,Context context) {
        if(context==null) return;
        if(context.type().equals("HOUSING_MINE"))housingTransfer(user,context.id());
        else if(context.type().startsWith("REPAIR_")) repairApplication(user,repairView(context.type()),context.id());
        else application(user,context.type().startsWith("LEAVE")?"LEAVE":"CERTIFICATE",context.id(),context.type().endsWith("REVIEW")?"REVIEW":"MINE");
    }
    public ToolResult execute(CampusPrincipal user,Call call,Context context) {
        identities.requirePermission(user,"agent:query");
        if(!capabilities(user).stream().anyMatch(c->c.name().equals(call.name()))) throw new ApiException(HttpStatus.FORBIDDEN,"TOOL_NOT_ALLOWED","当前身份不能使用这项查询");
        var a=call.arguments();
        Set<String> fields=switch(call.name()) {
            case "search_guides" -> Set.of("query"); case "my_timetable" -> Set.of("week","termId"); case "my_grades","my_exams" -> Set.of("termId");
            case "my_affairs" -> Set.of("kind","status"); case "application_status" -> Set.of("kind","id","view");
            case "my_repairs","repair_dispatch_queue","repair_work_queue" -> Set.of("status"); case "repair_status" -> Set.of("id","view"); case "housing_transfer_status" -> Set.of("id");
            case "campus_navigation" -> Set.of("destination","origin"); default -> Set.of();
        };
        if(a==null || !a.isObject() || a.properties().stream().anyMatch(e->!fields.contains(e.getKey()))) throw invalid();
        return switch(call.name()) {
            case "search_guides" -> knowledge(text(a,"query",null,200));
            case "my_timetable" -> timetable(user,text(a,"week",null,10),number(a,"termId"));
            case "my_grades","my_exams" -> learning(user,call.name(),number(a,"termId"));
            case "my_affairs" -> affairs(user,choice(a,"kind","ALL",Set.of("ALL","LEAVE","CERTIFICATE")),choice(a,"status","ALL",Set.of("ALL","DRAFT","SUBMITTED","RETURNED","APPROVED","COMPLETED","REJECTED","WITHDRAWN")));
            case "my_notice_summary" -> messages(user);
            case "my_review_pending" -> pending(user);
            case "application_status" -> {
                String kind=choice(a,"kind",context==null?null:context.type().startsWith("LEAVE")?"LEAVE":"CERTIFICATE",Set.of("LEAVE","CERTIFICATE"));
                Long id=number(a,"id"); if(id==null && context!=null && kind.equals(context.type().startsWith("LEAVE")?"LEAVE":"CERTIFICATE")) id=context.id();
                String view=choice(a,"view",context!=null && context.type().endsWith("REVIEW")?"REVIEW":"MINE",Set.of("MINE","REVIEW"));
                if(kind==null || id==null) yield result(call.name(),"NEEDS_INPUT","请先选择申请，或明确请假／证明种类和申请编号。",0,List.of(),List.of(),null);
                yield application(user,kind,id,view);
            }
            case "my_housing" -> housing(user);
            case "my_housing_transfers" -> housingTransfers(user);
            case "housing_transfer_status" -> {
                Long id=number(a,"id");if(id==null && context!=null && context.type().equals("HOUSING_MINE"))id=context.id();
                if(id==null)yield result(call.name(),"NEEDS_INPUT","请选择本人的调宿申请后查询。",0,List.of(),List.of(),"/housing/transfer");
                yield housingTransfer(user,id);
            }
            case "my_repairs" -> repairList(user,"my_repairs","mine",choice(a,"status","ALL",Set.copyOf(REPAIR_STATUSES)));
            case "repair_dispatch_queue" -> repairList(user,"repair_dispatch_queue","dispatch",choice(a,"status","ALL",Set.copyOf(REPAIR_STATUSES)));
            case "repair_work_queue" -> repairList(user,"repair_work_queue","work",choice(a,"status","ALL",Set.copyOf(REPAIR_STATUSES)));
            case "repair_status" -> {
                String fallback=context==null?null:repairView(context.type());
                String view=choice(a,"view",fallback,Set.of("MINE","DISPATCH","WORK"));
                Long id=number(a,"id"); if(id==null && context!=null && view!=null && view.equals(fallback)) id=context.id();
                if(view==null || id==null) yield result(call.name(),"NEEDS_INPUT","请先选择报修，或明确视图和工单编号。",0,List.of(),List.of(),null);
                yield repairApplication(user,view,id);
            }
            case "campus_navigation" -> navigation(text(a,"destination",null,80),text(a,"origin","南门",80));
            default -> throw invalid();
        };
    }
    private ToolResult housing(CampusPrincipal user) {
        var stay=housing.currentForAssistant(user);
        return result("my_housing",stay==null?"NO_DATA":"OK",stay==null?"暂无本人当前入住记录，请联系宿管核对。":"以下为本人当前房间，床位和负责人请进入我的住宿核对。",stay==null?0:1,
            stay==null?List.of():List.of(new Fact("housing:current","本人当前住宿",stay.campusName()+" · "+stay.buildingName()+" · "+stay.roomName(),"/housing/mine")),List.of(),"/housing/mine");
    }
    private ToolResult housingTransfers(CampusPrincipal user) {
        var rows=transfers.recentMine(user);
        return result("my_housing_transfers",rows.isEmpty()?"NO_DATA":"OK","展示本人最近至多 8 条调宿记录；原因、材料和完整处理意见请进入原申请。",rows.size(),rows.stream().map(this::housingFact).toList(),List.of(),"/housing/transfer");
    }
    private ToolResult housingTransfer(CampusPrincipal user,long id) {
        var row=transfers.mineDetail(user,id);var fact=housingFact(row);
        return result("housing_transfer_status","OK","已按当前账号重新核对本人申请。完整资料请在原申请查看。",1,List.of(fact),List.of(),fact.targetPath());
    }
    private Fact housingFact(Transfer r) {
        String status=switch(r.status()){case "SUBMITTED"->"待初审";case "COMPLETED"->"已完成";case "AWAITING_ASSIGNMENT"->"待安排";case "AWAITING_CHECKIN"->"待入住";case "CANCELLED"->"已取消";case "EXPIRED"->"已过期";default->statusName(r.status());};
        return new Fact("HOUSING:"+r.id(),"本人调宿申请 #"+r.id(),status+" · V"+r.version()+(r.cancelRequested()?" · 取消待宿管确认":"")+(r.reserveUntil()!=null && r.status().equals("AWAITING_CHECKIN")?" · 入住期限 "+r.reserveUntil().toString().replace('T',' '):""),"/housing/transfer?id="+r.id()+"&tab=details");
    }
    private ToolResult knowledge(String query) {
        var sources=guides.search(query);
        return result("search_guides",sources.isEmpty()?"NO_DATA":"OK",sources.isEmpty()?"没有找到有效指南，不能据此推测学校规则。":"以下为当前有效的演示指南，版本与完整原文可查看来源。",sources.size(),List.of(),sources,null);
    }
    private Term term(CampusPrincipal user,Long id,LocalDate date) {
        var choices=teaching.terms(user).stream().filter(t->id!=null?t.id()==id:!date.isBefore(t.startsOn()) && !date.isAfter(t.endsOn())).toList();
        if(choices.size()!=1) throw new ApiException(HttpStatus.CONFLICT,"TERM_SELECTION_REQUIRED","没有唯一覆盖查询日期的学期，请在学习页面查看，或明确要查询的学期编号");
        return choices.get(0);
    }
    private ToolResult timetable(CampusPrincipal user,String week,Long id) {
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        try { if(week!=null) date=LocalDate.parse(week); } catch(Exception ex) { throw invalid(); }
        if(date.getYear()<2000 || date.getYear()>2100) throw invalid();
        var selected=term(user,id,date); var monday=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        boolean teacher=!identities.permissions(user.id()).contains("learning:read");
        String path=(teacher?"/teaching/timetable":"/portal/learning/timetable")+"?term="+selected.id()+"&week="+monday;
        var rows=teaching.timetable(user,selected.id(),monday,teacher);
        var facts=rows.stream().limit(12).map(r->new Fact("lesson:"+r.id(),r.courseName(),period(r.startsAt(),r.endsAt())+" · "+r.classroom()+" · "+r.campusName(),path)).toList();
        return result("my_timetable","OK",selected.name()+" · "+monday+" 起一周，共 "+rows.size()+" 条安排；个人教学演示记录。",rows.size(),facts,List.of(),path);
    }
    private ToolResult learning(CampusPrincipal user,String name,Long id) {
        identities.requirePermission(user,"learning:read"); var selected=term(user,id,LocalDate.now(ZoneId.of("Asia/Shanghai")));
        if(name.equals("my_grades")) {
            var rows=teaching.studentGrades(user,selected.id());
            return result(name,"OK",selected.name()+" · 本人已发布成绩 "+rows.size()+" 条，未发布记录不展示。",rows.size(),
                rows.stream().limit(12).map(g->new Fact("grade:"+g.id(),g.courseName(),g.score()+" 分 · "+g.credits()+" 学分","/portal/learning/grades?term="+selected.id())).toList(),List.of(),"/portal/learning/grades?term="+selected.id());
        }
        var rows=teaching.studentExams(user,selected.id());
        return result(name,"OK",selected.name()+" · 本人已发布考试 "+rows.size()+" 场。",rows.size(),
            rows.stream().limit(12).map(e->new Fact("exam:"+e.id(),e.courseName(),period(e.startsAt(),e.endsAt())+" · "+e.classroom(),"/portal/learning/exams?term="+selected.id())).toList(),List.of(),"/portal/learning/exams?term="+selected.id());
    }
    private record TimedFact(Fact fact,LocalDateTime time) {}
    private ToolResult affairs(CampusPrincipal user,String kind,String status) {
        identities.requirePermission(user,"portal:read"); var rows=new ArrayList<TimedFact>(); long total=0; String filter=status.equals("ALL")?null:status;
        if(!kind.equals("CERTIFICATE") && !status.equals("COMPLETED")) {
            var page=leaves.list(user,"mine",filter,1,8); total+=page.total();
            page.items().forEach(r->rows.add(new TimedFact(fact("LEAVE",r.id(),r.status(),r.version(),r.updatedAt(),"mine"),r.updatedAt())));
        }
        if(!kind.equals("LEAVE") && !status.equals("APPROVED")) {
            var page=certificates.list(user,"mine",filter,1,8); total+=page.total();
            page.items().forEach(r->rows.add(new TimedFact(fact("CERTIFICATE",r.id(),r.status(),r.version(),r.updatedAt(),"mine"),r.updatedAt())));
        }
        return result("my_affairs","OK","本人当前筛选共 "+total+" 条，展示最近至多 8 条。处理意见和私有资料请进入原申请查看。",total,
            rows.stream().sorted(Comparator.comparing(TimedFact::time).reversed().thenComparing(x->x.fact().key())).limit(8).map(TimedFact::fact).toList(),List.of(),"/portal/affairs");
    }
    private Fact fact(String kind,long id,String status,int version,LocalDateTime time,String view) {
        String path=(view.equals("mine")?"/portal/":"/student-affairs/")+(kind.equals("LEAVE")?"leave":"certificates")+"?id="+id+"&tab=details";
        return new Fact(kind+":"+id,(kind.equals("LEAVE")?"请假":"在校证明")+"申请 #"+id,statusName(status)+" · V"+version+" · "+time.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")),path);
    }
    private ToolResult application(CampusPrincipal user,String kind,long id,String view) {
        String scope=view.equals("REVIEW")?"review":"mine"; Fact item;
        if(kind.equals("LEAVE")) { var r=leaves.detail(user,scope,id); item=fact(kind,id,r.status(),r.version(),r.updatedAt(),scope); }
        else { var r=certificates.detail(user,scope,id); item=fact(kind,id,r.status(),r.version(),r.updatedAt(),scope); }
        return result("application_status","OK","已重新核对当前身份和业务范围，资料详情请进入原申请。",1,List.of(item),List.of(),item.targetPath());
    }
    private String repairView(String type) { return switch(type) { case "REPAIR_MINE" -> "MINE"; case "REPAIR_DISPATCH" -> "DISPATCH"; case "REPAIR_WORK" -> "WORK"; default -> null; }; }
    private String repairScope(String view) { return switch(view) { case "MINE","mine" -> "mine"; case "DISPATCH","dispatch" -> "dispatch"; case "WORK","work" -> "work"; default -> throw invalid(); }; }
    private ToolResult repairList(CampusPrincipal user,String tool,String view,String status) {
        String scope=repairScope(view); var page=repairs.list(user,scope,"ALL".equals(status)?null:status,1,8);
        var facts=page.items().stream().map(item->repairFact(item,scope)).toList();
        String who=scope.equals("mine")?"本人":scope.equals("dispatch")?"当前派单范围":"当前分配";
        return result(tool,"OK",who+"共 "+page.total()+" 条，展示最近至多 8 条。联系方式、图片和故障说明请进入原工单查看。",page.total(),facts,List.of(),repairPath(scope,null));
    }
    private ToolResult repairApplication(CampusPrincipal user,String view,long id) {
        String scope=repairScope(view); var detail=repairs.detail(user,scope,id);
        var item=repairFact(new Item(detail.id(),detail.reporterName(),detail.title(),detail.campusId(),detail.campusName(),detail.locationId(),detail.locationName(),detail.category(),detail.priority(),detail.status(),detail.technicianName(),detail.version(),detail.updatedAt(),detail.cancellationPending(),detail.confirmationOverdue()),scope);
        return result("repair_status","OK","已重新核对当前身份和业务范围。联系方式、图片和故障说明请进入原工单查看。",1,List.of(item),List.of(),item.targetPath());
    }
    private Fact repairFact(Item item,String view) {
        String place=item.locationName()==null || item.locationName().isBlank()?"地点待选择":item.locationName();
        String text=repairStatusName(item.status())+" · "+repairCategoryName(item.category())+" · "+place+" · V"+item.version()+" · "+item.updatedAt().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
            +(item.cancellationPending()?" · 取消待处理":"")+(item.confirmationOverdue()?" · 超过48小时待验收":"");
        return new Fact("REPAIR:"+item.id(),"校园报修 #"+item.id(),text,repairPath(view,item.id()));
    }
    private String repairPath(String view,Long id) {
        String base=switch(view) { case "mine" -> "/repairs/mine"; case "dispatch" -> "/logistics/repairs/dispatch"; default -> "/logistics/repairs/tasks"; };
        return id==null?base:base+"?id="+id+"&tab=details";
    }
    private String repairStatusName(String status) { return switch(status) { case "DRAFT" -> "草稿"; case "SUBMITTED" -> "待后勤分诊"; case "RETURNED" -> "待补充资料"; case "ASSIGNED" -> "待维修接单"; case "IN_PROGRESS" -> "维修处理中"; case "AWAITING_CONFIRMATION" -> "待本人验收"; case "COMPLETED" -> "已完成"; case "REJECTED" -> "已拒绝"; case "WITHDRAWN" -> "已撤回"; case "CANCELLED" -> "已取消"; default -> "未知状态"; }; }
    private String repairCategoryName(String category) { return switch(category==null?"":category) { case "WATER" -> "给排水"; case "ELECTRICITY" -> "电气照明"; case "NETWORK" -> "网络"; case "FURNITURE" -> "家具"; case "DOOR_WINDOW" -> "门窗"; case "EQUIPMENT" -> "设备"; case "OTHER" -> "其他"; default -> "类别待填写"; }; }
    private ToolResult messages(CampusPrincipal user) {
        var page=notices.list(user,null,1,1); String path=identities.permissions(user.id()).contains("portal:read")?"/portal/notices":"/notices";
        return result("my_notice_summary","OK","本人共 "+page.total()+" 条消息，其中未读 "+page.unreadCount()+" 条。",page.unreadCount(),List.of(),List.of(),path);
    }
    private ToolResult pending(CampusPrincipal user) {
        var p=identities.permissions(user.id()); var items=new ArrayList<Fact>(); long total=0;
        if(p.contains("leave:review")) { long count=leaves.list(user,"review","SUBMITTED",1,1).total(); total+=count; items.add(new Fact("review:leave","班级请假待办",count+" 条 · 当前负责行政班","/student-affairs/leave")); }
        if(p.contains("certificate:review")) { long count=certificates.list(user,"review","SUBMITTED",1,1).total(); total+=count; items.add(new Fact("review:certificate","学院证明待办",count+" 条 · 当前授权学院","/student-affairs/certificates")); }
        if(items.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN,"TOOL_NOT_ALLOWED","当前岗位没有请假或证明处理范围");
        return result("my_review_pending","OK","当前待审核 "+total+" 条；退回或结束的记录不继续计入待办。",total,items,List.of(),null);
    }
    private record NavTarget(String id, String name, String category, String guide, List<String> aliases) {}
    private static final List<NavTarget> NAV_TARGETS = List.of(
        new NavTarget("east-library", "图书馆", "学习资源", "位于中环路核心环抱形建筑，南向开敞内庭。示意路线：从南门沿人文大道向北可达（场景不用于实地导航，距离未实测）。", List.of("图书馆", "图书大厦", "借书", "自习室", "阅览室")),
        new NavTarget("east-jinxiu", "毓秀楼", "学生公寓", "东校区学生公寓，位于文华楼组团东侧、临近东侧水系（秋月塘）西岸。示意路线：从南门向北沿人文大道，在文华楼东侧右转可达（不用于实地导航）。", List.of("毓秀楼", "毓秀")),
        new NavTarget("east-fenghua", "风华楼", "学生公寓", "东校区学生公寓，位于毓秀楼南侧、紧邻水系西岸。示意路线：从南门进入后向东北方向文华楼东侧可达（不用于实地导航）。", List.of("风华楼", "风华")),
        new NavTarget("east-shuhua", "书华楼", "学生公寓", "东校区学生公寓，位于中环路北侧、致远楼西侧。示意路线：从南门沿中环路西北方向可达（不用于实地导航）。", List.of("书华楼", "书华")),
        new NavTarget("east-zhiyuan", "致远楼", "学生公寓", "东校区学生公寓，位于书华楼东侧、静逸楼北侧，中环路北段。示意路线：从南门穿过中环路北行可达（不用于实地导航）。", List.of("致远楼", "致远")),
        new NavTarget("east-jingyi", "静逸楼", "学生公寓", "东校区学生公寓，位于中环路北侧、致远楼南侧，与锦绣餐厅由校内道路独立分隔。示意路线：从南门沿中环路向北可达（不用于实地导航）。", List.of("静逸楼", "静逸")),
        new NavTarget("east-jinxiu-dining", "锦绣餐厅", "生活餐饮", "东校区主要师生食堂（高德地图收录为锦绣食府），与静逸楼由道路自然分隔。从中环路西北侧步行即可抵达。", List.of("锦绣餐厅", "锦绣食府", "锦绣食堂", "北食堂", "锦绣")),
        new NavTarget("east-canteen-wenhua", "文华餐厅", "生活餐饮", "位于西南生活服务区、行政楼西侧。从南门进入沿人文大道向左侧生活区步行可达。", List.of("文华餐厅", "文华食堂", "南食堂", "食堂", "餐厅")),
        new NavTarget("east-gym", "红体馆", "文体场馆", "位于校园西北、英才大道西段，具有标志性近圆形屋顶。示意路线：从南门向西北经英才大道可达文体区（不用于实地导航；公开卫星未单独确认西门落点）。", List.of("红体馆", "弘德体育馆", "体育馆", "篮球馆", "球馆")),
        new NavTarget("east-swimming", "游泳馆", "文体场馆", "紧邻红体馆东南侧，英才大道南侧。从南门向西北方向文体区步行可达。", List.of("游泳馆", "游泳池")),
        new NavTarget("east-sports", "西侧体育场", "文体场馆", "包含标准田径场与成组球场。从南门向西经英才大道抵达体育区。", List.of("西侧体育场", "体育场", "操场", "田径场", "足球场", "跑道")),
        new NavTarget("east-admin", "行政楼", "综合办公", "位于人文大道中轴北端、文华餐厅东侧、学术报告厅西侧，为全校行政办公枢纽。", List.of("行政楼", "行政管理中心", "办公楼", "校办")),
        new NavTarget("east-auditorium", "学术报告厅", "公共建筑", "位于行政管理中心东侧、外环东路与水系西岸，用于学术交流与大型会议。", List.of("学术报告厅", "报告厅", "礼堂", "大礼堂")),
        new NavTarget("east-square", "英才广场", "校园景观", "位于图书馆西侧、体育区与艺术楼之间开阔铺装区域。", List.of("英才广场", "广场", "升旗台")),
        new NavTarget("east-xinke", "信科楼组团", "学院楼宇", "位于图书馆西南侧、人文大道西侧，包含计算机与软件学院、大数据管理中心。", List.of("信科楼", "信息工程学院", "软件学院", "大数据", "计算机学院")),
        new NavTarget("east-jiangong", "建工楼", "学院楼宇", "位于信科楼南侧、人文大道西侧，建筑工程教学核心场所。", List.of("建工楼")),
        new NavTarget("east-construction-training", "建工实训中心", "实验实训", "位于文华餐厅南侧、建工学院北侧，为工程技术实训基地。", List.of("建工实训中心", "实训中心", "实训基地", "车间")),
        new NavTarget("east-construction", "建工学院", "学院楼宇", "位于人文大道西南侧、建工实训中心南侧。", List.of("建工学院", "建筑工程学院")),
        new NavTarget("east-wenhua", "文华楼组团", "学院楼宇", "位于图书馆东南侧、中环路与人文大道交汇东侧，包含商学院、外国语学院、管理学院。", List.of("文华楼", "商学院", "外国语学院", "外语学院", "管理学院")),
        new NavTarget("east-art", "美术、音乐楼组团", "学院楼宇", "位于艺馨路西侧、游泳馆东侧，包含艺术设计学院与音乐学院。", List.of("美术楼", "音乐楼", "艺术楼", "艺术设计学院", "音乐学院")),
        new NavTarget("east-medical", "医学院、药学院组团", "学院楼宇", "位于南部人文大道东侧、邻外环东路，包含药学院与医学院教学区。", List.of("医学院", "药学院", "医学楼")),
        new NavTarget("east-clinic", "医务室", "医疗服务", "校园基础医疗健康服务点，位于校区服务保障区。", List.of("医务室", "校医院", "卫生所", "看病", "校医")),
        new NavTarget("east-affairs", "学生综合事务服务中心", "综合服务", "位于外环东路侧，提供一站式学生综合事务办理。", List.of("学生综合事务服务中心", "综合事务服务中心", "学生事务中心", "事务中心")),
        new NavTarget("east-water", "东侧水系", "校园水系", "东校区纵向景观水系（公开文本常称秋月塘），沿岸设滨水步道与绿化景观。", List.of("东侧水系", "秋月塘", "水系", "景观湖", "湖边", "东湖")),
        new NavTarget("east-gate", "东门", "出入口", "校园东侧出入口，邻近万福路。", List.of("东门", "东大门", "万福路门")),
        new NavTarget("south-gate", "南门", "出入口", "学院路北侧校园主入口，人文大道南起点。", List.of("南门", "正门", "主校门", "南大门", "大门", "学校大门")),
        new NavTarget("north-gate", "北门", "出入口", "外环北路北侧校园出入口。", List.of("北门", "北大门")),
        new NavTarget("east-h8", "H8", "学生公寓", "东校区学生公寓，位于外环北路南侧、书华楼西北侧。示意路线：从南门向北经中环路可达北侧生活区（不用于实地导航）。", List.of("H8", "H8楼", "H8主楼", "H8副楼")),
        new NavTarget("east-h1", "H1", "学生公寓", "东校区学生公寓，位于体育场东侧、信科楼组团西侧（分布图位置点，楼体轮廓待对应）。示意导览不用于实地导航。", List.of("H1", "H1楼")),
        new NavTarget("east-h2", "H2", "学生公寓", "东校区学生公寓，位于H1南侧、文华餐厅西北侧（分布图位置点，楼体轮廓待对应）。示意导览不用于实地导航。", List.of("H2", "H2楼"))
    );
    private NavTarget resolveNavTarget(String text) {
        if(text==null || text.isBlank()) return null;
        String clean=text.trim();
        for(var t:NAV_TARGETS) {
            for(var alias:t.aliases()) {
                if(clean.equalsIgnoreCase(alias) || clean.contains(alias) || alias.contains(clean)) return t;
            }
        }
        return null;
    }
    private ToolResult navigation(String destination,String origin) {
        String from=(origin==null || origin.isBlank())?"南门":origin.trim();
        NavTarget target=resolveNavTarget(destination);
        if(target==null) {
            return result("campus_navigation","NEEDS_INPUT","未找到指定校园地点。支持查询图书馆、毓秀楼、静逸楼、锦绣餐厅、红体馆、行政楼等 30 处主要场所。",0,List.of(),List.of(),"/campus/explore");
        }
        String path="/campus/explore?navigate="+target.id();
        Fact fact=new Fact("nav:"+target.id(),target.name()+"（"+target.category()+"）",target.guide(),path);
        String msg=String.format("已给出从【%s】前往【%s】的示意导览。点击查看 3D 校园全景与高亮漫游（场景不用于实地导航，距离未实测）。",from,target.name());
        return result("campus_navigation","OK",msg,1,List.of(fact),List.of(),path);
    }
    public ToolResult failure(String name,ApiException ex) {
        return result(name,ex.status().value()==403?"DENIED":ex.status().value()==404?"NOT_FOUND":ex.code().equals("TERM_SELECTION_REQUIRED")?"NEEDS_INPUT":"ERROR",ex.getMessage(),0,List.of(),List.of(),null);
    }
    private ToolResult result(String name,String status,String message,long total,List<Fact> facts,List<Guide> sources,String href) { return new ToolResult(name,TITLES.getOrDefault(name,"查询未执行"),status,message,total,facts,sources,href); }
    public Call call(String name,Map<String,?> arguments) { return new Call(name,json.valueToTree(arguments)); }
    private String text(JsonNode a,String name,String fallback,int max) { var x=a.path(name); if(x.isMissingNode() || x.isNull()) return fallback; if(!x.isString() || x.asText().isBlank() || x.asText().length()>max) throw invalid(); return x.asText().trim(); }
    private String choice(JsonNode a,String name,String fallback,Set<String> options) { String x=text(a,name,fallback,40); if(x!=null && !options.contains(x)) throw invalid(); return x; }
    private Long number(JsonNode a,String name) { var x=a.path(name); if(x.isMissingNode() || x.isNull()) return null; if(!x.isIntegralNumber() || !x.asText().matches("[1-9][0-9]{0,17}")) throw invalid(); return x.asLong(); }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENTS","查询参数无效，未读取其他人的资料或修改业务"); }
    private String statusName(String status) { return switch(status) { case "DRAFT" -> "草稿"; case "SUBMITTED" -> "待审核"; case "RETURNED" -> "待补交"; case "APPROVED" -> "已通过"; case "COMPLETED" -> "办理完成"; case "REJECTED" -> "已拒绝"; case "WITHDRAWN" -> "已撤回"; default -> "未知状态"; }; }
    private String period(LocalDateTime start,LocalDateTime end) { return start.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"))+"—"+end.format(DateTimeFormatter.ofPattern(start.toLocalDate().equals(end.toLocalDate())?"HH:mm":"MM月dd日 HH:mm")); }
}
