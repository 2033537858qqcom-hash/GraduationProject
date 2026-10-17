package cn.zzuit.campus.repair;

import static cn.zzuit.campus.repair.RepairModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.notice.NoticeWriter;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RepairService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final RepairSettingsService settings;
    private final NoticeWriter notices;
    private final Clock clock;
    private final int reminderHours;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final List<String> STATES=List.of("DRAFT","SUBMITTED","RETURNED","ASSIGNED","IN_PROGRESS","AWAITING_CONFIRMATION","COMPLETED","REJECTED","WITHDRAWN","CANCELLED");
    private static final String SELECT="SELECT r.*,CASE WHEN "+RepairSettingsService.RESIDENCY_SQL+" THEN TRUE ELSE FALSE END location_eligible,u.full_name technician_name,(SELECT c.created_at FROM repair_completion c WHERE c.request_id=r.id AND c.result_round=r.completion_round) result_at FROM repair_request r LEFT JOIN app_user u ON u.id=r.technician_id";
    private static final String SCOPE="""
        EXISTS(SELECT 1 FROM repair_staff_scope s WHERE s.user_id=:user AND s.enabled=TRUE
        AND s.capability=:capability AND s.campus_id=r.campus_id AND s.category=r.category
        AND (s.location_id IS NULL OR s.location_id=r.location_id))
        """;
    public RepairService(JdbcClient jdbc,IdentityService identities,RepairSettingsService settings,NoticeWriter notices,Clock clock,@Value("${app.repair.reminders.hours:48}") int reminderHours) {
        if(reminderHours<1 || reminderHours>8760)throw new IllegalArgumentException("Repair reminder hours must be between 1 and 8760");
        this.jdbc=jdbc;this.identities=identities;this.settings=settings;this.notices=notices;this.clock=clock;this.reminderHours=reminderHours;
    }
    private void lock() { jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update(); }
    private void permission(CampusPrincipal user,String view) {
        identities.requirePermission(user,switch(view) {case "mine" -> "repair:apply";case "dispatch" -> "repair:dispatch";case "work" -> "repair:work";default -> throw invalid("报修视图无效");});
    }
    private Row find(long id) { return jdbc.sql(SELECT+" WHERE r.id=:id").param("id",id).query(this::row).optional().orElseThrow(this::missing); }
    private Row authorized(CampusPrincipal user,String view,long id) {
        permission(user,view);var r=find(id);
        if(view.equals("mine")) { if(r.reporter()!=user.id())throw missing(); }
        else if(r.status().equals("DRAFT") || r.reporter()==user.id() || r.location()==null || r.campus()==null ||
            (view.equals("work") && !Objects.equals(r.technician(),user.id())) ||
            !settings.matches(user,view.equals("dispatch")?"DISPATCH":"WORK",r.campus(),r.category(),r.location()))throw missing();
        return r;
    }
    public Detail detail(CampusPrincipal user,String view,long id) {return detail(authorized(user,view,id),view);}
    private Detail detail(Row r,String view) {
        var completions=jdbc.sql("SELECT * FROM repair_completion WHERE request_id=:id ORDER BY result_round DESC").param("id",r.id())
            .query((s,n)->new Completion(s.getInt("result_round"),s.getString("technician_name"),s.getString("comment"),completionImages(s.getLong("id")),time(s,"created_at"))).list();
        var evaluation=jdbc.sql("SELECT * FROM repair_evaluation WHERE request_id=:id").param("id",r.id())
            .query((s,n)->new Evaluation(s.getInt("stars"),s.getString("comment"),time(s,"created_at"))).optional().orElse(null);
        var assignments=jdbc.sql("""
            SELECT a.id, a.technician_id, u.full_name technician_name, a.actor_id, act.full_name actor_name,
                   a.comment, a.assigned_at, a.ended_at
            FROM repair_assignment a
            JOIN app_user u ON u.id = a.technician_id
            JOIN app_user act ON act.id = a.actor_id
            WHERE a.request_id = :id
            ORDER BY a.id ASC
            """).param("id", r.id())
            .query((s, n) -> new AssignmentRecord(
                s.getLong("id"),
                s.getLong("technician_id"),
                s.getString("technician_name"),
                s.getLong("actor_id"),
                s.getString("actor_name"),
                s.getString("comment"),
                time(s, "assigned_at"),
                time(s, "ended_at")
            )).list();
        return new Detail(r.id(),r.reporterName(),r.title(),r.campus(),r.campusName(),r.location(),r.locationName(),r.category(),r.priority(),r.status(),r.version(),
            r.description(),r.locationDetail(),r.contact(),r.contactWindow(),r.technician(),r.technicianName(),r.round(),r.comment(),actions(r,view,evaluation!=null),
            images(r.id(),null,null,true),completions,assignments,evaluation,r.submitted(),r.confirmed(),r.created(),r.updated(),cancellation(r.id()),r.cancellation()!=null,overdue(r),r.locationEligible(),r.locationEligible()?"":"原入住关系已结束或住宿权限已变化，旧工单停止维修办理，历史资料继续保留。后续故障请按当前住宿重新报修。");
    }
    private List<String> actions(Row r,String view,boolean evaluated) {
        var a=new ArrayList<String>();
        if(r.cancellation()!=null)return view.equals("mine")?List.of("withdraw-cancellation"):view.equals("dispatch")?List.of("approve-cancellation","reject-cancellation"):List.of();
        if(view.equals("mine")) {
            if(List.of("DRAFT","RETURNED").contains(r.status()))a.addAll(List.of("edit","submit","upload-fault","remove-fault"));
            if(List.of("DRAFT","SUBMITTED","RETURNED").contains(r.status()))a.add("withdraw");
            if(r.status().equals("AWAITING_CONFIRMATION"))a.addAll(List.of("confirm","reopen"));
            if(r.status().equals("COMPLETED") && !evaluated)a.add("evaluation");
            if(List.of("ASSIGNED","IN_PROGRESS","AWAITING_CONFIRMATION").contains(r.status()))a.add("request-cancellation");
        } else if(view.equals("dispatch")) {
            if(r.status().equals("SUBMITTED"))a.addAll(List.of("return","reject","assign","priority"));
            if(List.of("ASSIGNED","IN_PROGRESS").contains(r.status()))a.addAll(List.of("reassign","priority"));
        } else {
            if(r.status().equals("ASSIGNED"))a.add("accept");
            if(r.status().equals("IN_PROGRESS"))a.addAll(List.of("progress","unable","complete","upload-progress","upload-result","remove-result"));
        }
        if(!r.locationEligible())a.removeIf(action->!List.of("withdraw","request-cancellation","evaluation","return","reject").contains(action)
            && !(action.equals("edit") && r.submitted()==null));
        return List.copyOf(a);
    }
    private String where(String view) {
        if(view.equals("mine"))return " WHERE r.reporter_id=:user";
        return " WHERE r.status<>'DRAFT' AND r.reporter_id<>:user AND "+SCOPE+(view.equals("work")?" AND r.technician_id=:user":"");
    }
    private HashMap<String,Object> params(CampusPrincipal user,String view) {var p=new HashMap<String,Object>();p.put("user",user.id());if(!view.equals("mine"))p.put("capability",view.equals("dispatch")?"DISPATCH":"WORK");return p;}
    public Page<Item> list(CampusPrincipal user,String view,String status,int page,int size) {
        return list(user,view,status,null,page,size);
    }
    public Page<Item> list(CampusPrincipal user,String view,String status,Boolean cancellationPending,int page,int size) {
        permission(user,view);pagination(page,size);if(status!=null && !STATES.contains(status))throw invalid("报修状态无效");
        var p=params(user,view);String where=where(view);if(status!=null){where+=" AND r.status=:status";p.put("status",status);}
        if(cancellationPending!=null)where+=cancellationPending?" AND r.current_cancellation_id IS NOT NULL":" AND r.current_cancellation_id IS NULL";
        long total=jdbc.sql("SELECT COUNT(*) FROM repair_request r"+where).params(p).query(Long.class).single();
        var rows=jdbc.sql(SELECT+where+" ORDER BY r.updated_at DESC,r.id DESC LIMIT :limit OFFSET :offset").params(p).param("limit",size).param("offset",((long)page-1)*size)
            .query((s,n)->item(row(s,n))).list();return new Page<>(rows,total,page,size);
    }
    public Summary summary(CampusPrincipal user) {
        var fresh=identities.current(user.id()).orElseThrow(this::missing);
        long mineAwaiting=0,mineReturned=0,dispatch=0,assigned=0,inProgress=0,mineCancel=0,dispatchCancel=0;
        if(fresh.permissions().contains("repair:apply")){permission(user,"mine");mineAwaiting=count(user,"mine","AWAITING_CONFIRMATION");mineReturned=count(user,"mine","RETURNED");mineCancel=pendingCancellationCount(user,"mine");}
        if(fresh.permissions().contains("repair:dispatch")){permission(user,"dispatch");dispatch=count(user,"dispatch","SUBMITTED");dispatchCancel=pendingCancellationCount(user,"dispatch");}
        if(fresh.permissions().contains("repair:work")){permission(user,"work");assigned=count(user,"work","ASSIGNED");inProgress=count(user,"work","IN_PROGRESS");}
        if(!fresh.permissions().stream().anyMatch(p->List.of("repair:apply","repair:dispatch","repair:work").contains(p)))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","没有报修业务权限");
        return new Summary(mineAwaiting,mineReturned,dispatch,assigned,inProgress,mineCancel,dispatchCancel);
    }
    private long count(CampusPrincipal u,String view,String status) {return jdbc.sql("SELECT COUNT(*) FROM repair_request r"+where(view)+" AND r.status=:status AND r.current_cancellation_id IS NULL"+(view.equals("dispatch")?"":" AND "+RepairSettingsService.RESIDENCY_SQL)).params(params(u,view)).param("status",status).query(Long.class).single();}
    private long pendingCancellationCount(CampusPrincipal u,String view) {return jdbc.sql("SELECT COUNT(*) FROM repair_request r"+where(view)+" AND r.current_cancellation_id IS NOT NULL").params(params(u,view)).query(Long.class).single();}
    @Transactional public Detail save(CampusPrincipal user,Long id,Draft raw) {
        lock();permission(user,"mine");var input=normalized(raw);String digest=digest(input);
        if(id!=null)authorized(user,"mine",id);
        Long repeated=repeat(user,id,"SAVE",input.requestKey(),digest);
        if(repeated!=null)return detail(user,"mine",repeated);
        validateDraft(user,input);
        if(id==null) {
            if(input.version()!=0)stale();
            var key=new GeneratedKeyHolder();var p=fields(user,input);p.put("user",user.id());p.put("name",actor(user.id()));p.put("key",input.requestKey());
            jdbc.sql("INSERT INTO repair_request(reporter_id,reporter_name,request_key,campus_id,campus_name,location_id,location_name,category,title,description,location_detail,contact,contact_window,housing_assignment_id) VALUES(:user,:name,:key,:campus,:campusName,:location,:locationName,:category,:title,:description,:locationDetail,:contact,:contactWindow,:stay)")
                .params(p).update(key,"id");id=Objects.requireNonNull(key.getKey()).longValue();
        } else {
            var r=authorized(user,"mine",id);version(r.version(),input.version());editable(r);if(r.submitted()!=null)requireResidency(r);
            if(r.submitted()!=null && (!Objects.equals(r.location(),input.locationId()) || !r.category().equals(input.category())))conflict("提交后地点和类别不能替换，请撤回或另行处理原工单");
            var p=fields(user,input);p.put("id",id);
            // Keep the submitted location snapshot when editing returned descriptive information.
            jdbc.sql("UPDATE repair_request SET campus_id=:campus,location_id=:location,category=:category,title=:title,description=:description,location_detail=:locationDetail,contact=:contact,contact_window=:contactWindow,"+
                (r.submitted()==null?"campus_name=:campusName,location_name=:locationName,housing_assignment_id=:stay,":"")+"version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").params(p).update();
        }
        capture(user,id,"SAVE",input.requestKey(),digest,"保存报修资料");return detail(user,"mine",id);
    }
    private Draft normalized(Draft d) {return new Draft(d.requestKey(),d.version(),d.locationId(),text(d.category()),text(d.title()),text(d.description()),text(d.locationDetail()),text(d.contact()),text(d.contactWindow()));}
    private void validateDraft(CampusPrincipal user,Draft d) {
        if(d.locationId()==null && List.of(d.category(),d.title(),d.description(),d.locationDetail(),d.contact(),d.contactWindow()).stream().allMatch(String::isBlank))throw invalid("请填写报修信息后再保存草稿");
        if(!d.contact().isBlank() && !d.contact().matches("(?:\\+?[0-9][0-9 -]{6,23}|[^\\s@]+@[^\\s@]+\\.[^\\s@]+)"))throw invalid("联系方式须为手机号码或邮箱");
        if(d.locationId()!=null)settings.applicationLocation(user,d.locationId());
    }
    private HashMap<String,Object> fields(CampusPrincipal user,Draft d) {
        var p=new HashMap<String,Object>();p.put("location",d.locationId());p.put("campus",null);p.put("campusName","");p.put("locationName","");p.put("stay",null);
        if(d.locationId()!=null) {var source=settings.applicationLocation(user,d.locationId());if(source.dormRoomId()!=null)p.put("stay",settings.currentAssignment(user,source.dormRoomId()));}
        if(d.locationId()!=null){var l=jdbc.sql("SELECT l.campus_id,l.name,c.name campus_name FROM repair_location l JOIN campus c ON c.id=l.campus_id WHERE l.id=:id").param("id",d.locationId()).query((r,n)->List.of(r.getLong(1),r.getString(2),r.getString(3))).single();p.put("campus",l.get(0));p.put("locationName",l.get(1));p.put("campusName",l.get(2));}
        p.put("category",d.category());p.put("title",d.title());p.put("description",d.description());p.put("locationDetail",d.locationDetail());p.put("contact",d.contact());p.put("contactWindow",d.contactWindow());return p;
    }
    @Transactional public Detail transition(CampusPrincipal user,String view,long id,String action,Action raw) {
        lock();var r=authorized(user,view,id);var input=new Action(raw.requestKey(),raw.version(),text(raw.comment()),raw.technicianId(),raw.resultRound(),raw.stars(),raw.priority());String digest=digest(input);
        if(repeat(user,id,action,input.requestKey(),digest)!=null)return detail(user,view,id);
        version(r.version(),input.version());
        var allowed=actions(r,view,jdbc.sql("SELECT COUNT(*) FROM repair_evaluation WHERE request_id=:id").param("id",id).query(Long.class).single()>0);
        if(!allowed.contains(action))conflict("工单状态或权限已变化，当前不能执行此操作");
        if(!action.equals("evaluation") && input.comment().isBlank())throw invalid("请填写处理说明");
        String next=r.status();
        switch(action) {
            case "submit" -> {
                if(r.location()==null || r.category().isBlank() || r.title().isBlank() || r.description().isBlank() || r.contact().isBlank())throw invalid("提交前请补齐地点、类别、故障标题、说明及联系方式");
                settings.applicationLocation(user,r.location());
                if(dispatchers(r).isEmpty())conflict("该地点和类别尚未配置有效后勤人员，草稿已保留，请联系管理员");
                jdbc.sql("UPDATE repair_request SET submitted_at=COALESCE(submitted_at,CURRENT_TIMESTAMP) WHERE id=:id").param("id",id).update();next="SUBMITTED";
            }
            case "withdraw" -> next="WITHDRAWN";
            case "return" -> next="RETURNED";
            case "reject" -> next="REJECTED";
            case "assign","reassign" -> {
                if(input.technicianId()==null || technicians(user,id).stream().noneMatch(t->t.id()==input.technicianId()))throw invalid("请选择当前具维修资格且范围匹配的人员");
                if(action.equals("reassign") && Objects.equals(input.technicianId(),r.technician()))conflict("请选择其他维修人员，当前工单已分配给此人");
                endAssignment(id);
                jdbc.sql("UPDATE repair_attachment SET current=FALSE WHERE request_id=:id AND purpose='RESULT' AND result_round>:round").param("id",id).param("round",r.round()).update();
                jdbc.sql("INSERT INTO repair_assignment(request_id,technician_id,actor_id,comment) VALUES(:id,:tech,:actor,:comment)").param("id",id).param("tech",input.technicianId()).param("actor",user.id()).param("comment",input.comment()).update();
                jdbc.sql("UPDATE repair_request SET technician_id=:tech WHERE id=:id").param("tech",input.technicianId()).param("id",id).update();next="ASSIGNED";
            }
            case "accept" -> next="IN_PROGRESS";
            case "progress" -> { }
            case "unable" -> {
                endAssignment(id);jdbc.sql("UPDATE repair_request SET technician_id=NULL WHERE id=:id").param("id",id).update();
                jdbc.sql("UPDATE repair_attachment SET current=FALSE WHERE request_id=:id AND purpose='RESULT' AND result_round>:round").param("id",id).param("round",r.round()).update();next="SUBMITTED";
            }
            case "complete" -> {
                jdbc.sql("INSERT INTO repair_completion(request_id,result_round,technician_id,technician_name,comment) VALUES(:id,:round,:tech,:name,:comment)").param("id",id).param("round",r.round()+1).param("tech",user.id()).param("name",actor(user.id())).param("comment",input.comment()).update();
                jdbc.sql("INSERT INTO repair_completion_image(completion_id,attachment_id) SELECT c.id,a.id FROM repair_completion c JOIN repair_attachment a ON a.request_id=c.request_id AND a.purpose='RESULT' AND a.result_round=c.result_round AND a.current=TRUE WHERE c.request_id=:id AND c.result_round=:round").param("id",id).param("round",r.round()+1).update();
                jdbc.sql("UPDATE repair_request SET completion_round=completion_round+1 WHERE id=:id").param("id",id).update();next="AWAITING_CONFIRMATION";
            }
            case "reopen","confirm" -> {
                if(input.resultRound()==null || input.resultRound()!=r.round())conflict("维修结果轮次已变化，请核对最新结果");
                if(action.equals("reopen"))next="IN_PROGRESS";
                else {next="COMPLETED";endAssignment(id);jdbc.sql("UPDATE repair_request SET confirmed_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).update();}
            }
            case "evaluation" -> {
                if(input.stars()==null || input.resultRound()==null || input.resultRound()!=r.round() || input.comment().length()>500)throw invalid("请核对结果轮次，选择 1—5 星并填写最多 500 字评价");
                jdbc.sql("INSERT INTO repair_evaluation(request_id,reporter_id,result_round,stars,comment) VALUES(:id,:user,:round,:stars,:comment)").param("id",id).param("user",user.id()).param("round",r.round()).param("stars",input.stars()).param("comment",input.comment()).update();
            }
            case "priority" -> {if(input.priority()==null)throw invalid("请选择处理优先级");jdbc.sql("UPDATE repair_request SET priority=:p WHERE id=:id").param("p",input.priority()).param("id",id).update();}
            default -> throw invalid("操作无效");
        }
        jdbc.sql("UPDATE repair_request SET status=:status,processing_comment=:comment,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("status",next).param("comment",input.comment()).param("id",id).update();
        capture(user,id,action,input.requestKey(),digest,input.comment());sendNotices(r,find(id),action);
        // The unable action relinquishes access immediately; return the resulting state without a new worker read.
        return detail(find(id),view);
    }
    public List<Technician> technicians(CampusPrincipal user,long id) {
        var r=authorized(user,"dispatch",id);if(!r.locationEligible())return List.of();return qualified(r,"WORK").stream().map(u->new Technician(u.id(),u.name(),u.department())).toList();
    }
    private Cancellation cancellation(long id) {
        return jdbc.sql("""
            SELECT c.*,u.full_name requester_name,d.full_name decision_actor_name FROM repair_cancellation c
            JOIN app_user u ON u.id=c.requester_id LEFT JOIN app_user d ON d.id=c.decision_actor_id
            WHERE c.request_id=:id ORDER BY c.id DESC LIMIT 1
            """).param("id",id).query((r,n)->new Cancellation(r.getLong("id"),r.getString("reason"),r.getString("status"),r.getString("requester_name"),r.getString("decision_actor_name"),r.getString("decision_comment"),time(r,"created_at"),time(r,"resolved_at"))).optional().orElse(null);
    }
    @Transactional public Detail cancel(CampusPrincipal user,String view,long id,String operation,CancelAction raw) {
        lock();var r=authorized(user,view,id);
        String action=switch(operation){case "request" -> "CANCEL_REQUEST";case "withdraw" -> "CANCEL_WITHDRAW";case "decide" -> "APPROVE".equals(raw.decision())?"CANCEL_APPROVE":"CANCEL_REJECT";default -> throw invalid("取消操作无效");};
        var input=new CancelAction(raw.requestKey(),raw.version(),raw.cancellationId(),raw.decision(),text(raw.comment()));String digest=digest(input);
        if(repeat(user,id,action,input.requestKey(),digest)!=null)return detail(user,view,id);
        version(r.version(),input.version());if(input.comment().isBlank())throw invalid("请填写取消或处理说明");
        if(operation.equals("request")) {
            if(!view.equals("mine") || !actions(r,view,false).contains("request-cancellation"))conflict("当前状态不能申请取消，或已有待处理取消请求");
            if(input.cancellationId()!=null || input.decision()!=null)throw invalid("新取消申请不能指定旧请求或处理决定");
            var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO repair_cancellation(request_id,requester_id,reason) VALUES(:id,:user,:reason)").param("id",id).param("user",user.id()).param("reason",input.comment()).update(key,"id");
            jdbc.sql("UPDATE repair_request SET current_cancellation_id=:cancel WHERE id=:id").param("cancel",Objects.requireNonNull(key.getKey()).longValue()).param("id",id).update();
        } else {
            if(r.cancellation()==null || !Objects.equals(r.cancellation(),input.cancellationId()))conflict("取消请求已处理或编号已变化，请核对当前请求");
            var current=cancellation(id);if(current==null || current.id()!=r.cancellation() || !current.status().equals("PENDING"))conflict("取消请求已不在待处理状态");
            String state;
            if(operation.equals("withdraw")) {
                if(!view.equals("mine") || input.decision()!=null)throw invalid("只有本人可以撤销待处理取消申请");state="WITHDRAWN";
            } else {
                if(!view.equals("dispatch") || input.decision()==null)throw invalid("请由当前后勤岗位明确同意或拒绝取消");
                state=input.decision().equals("APPROVE")?"APPROVED":"REJECTED";
            }
            jdbc.sql("UPDATE repair_cancellation SET status=:status,decision_actor_id=:user,decision_comment=:comment,resolved_at=CURRENT_TIMESTAMP WHERE id=:cancel")
                .param("status",state).param("user",user.id()).param("comment",input.comment()).param("cancel",r.cancellation()).update();
            jdbc.sql("UPDATE repair_request SET current_cancellation_id=NULL WHERE id=:id").param("id",id).update();
            if(state.equals("APPROVED")) {
                endAssignment(id);jdbc.sql("UPDATE repair_request SET status='CANCELLED',technician_id=NULL WHERE id=:id").param("id",id).update();
                jdbc.sql("UPDATE repair_attachment SET current=FALSE WHERE request_id=:id AND purpose='RESULT' AND result_round>:round").param("id",id).param("round",r.round()).update();
            }
        }
        jdbc.sql("UPDATE repair_request SET processing_comment=:comment,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("comment",input.comment()).param("id",id).update();
        capture(user,id,action,input.requestKey(),digest,input.comment());
        String message=switch(action){case "CANCEL_REQUEST" -> "报修人申请取消，维修和验收暂时暂停，等待后勤决定。";case "CANCEL_WITHDRAW" -> "报修人已撤销取消申请，请查看当前工单的可办操作。";case "CANCEL_APPROVE" -> "后勤已同意取消，工单关闭，原任务及资料保留历史。";default -> "后勤未同意取消，请查看处理意见及当前工单的可办操作。";};
        notices.write(r.reporter(),"报修取消进度更新","REPAIR",id,"MINE",message);
        for(var person:dispatchers(r))notices.write(person.id(),"报修取消事务更新","REPAIR",id,"DISPATCH",message);
        if(r.technician()!=null)notices.write(r.technician(),"维修任务取消进度更新","REPAIR",id,"WORK",message);
        return detail(user,view,id);
    }
    private boolean overdue(Row r) {
        return r.locationEligible() && r.status().equals("AWAITING_CONFIRMATION") && r.cancellation()==null && r.resultAt()!=null && !r.resultAt().isAfter(LocalDateTime.now(clock).minusHours(reminderHours));
    }
    public Statistics statistics(CampusPrincipal user,LocalDate from,LocalDate to) {
        permission(user,"dispatch");LocalDate today=LocalDate.now(clock);
        if(from==null && to==null){from=today.minusDays(29);to=today.plusDays(1);}
        if(from==null || to==null || !to.isAfter(from) || to.isAfter(from.plusDays(366)))throw invalid("统计区间须为 1—366 天，结束日期不包含当天");
        var p=params(user,"dispatch");p.put("from",Timestamp.valueOf(from.atStartOfDay()));p.put("to",Timestamp.valueOf(to.atStartOfDay()));
        String scope=where("dispatch")+" AND r.submitted_at>=:from AND r.submitted_at<:to";
        var groups=jdbc.sql("""
            SELECT r.status,r.category,COUNT(*) total,
              SUM(CASE WHEN r.current_cancellation_id IS NOT NULL THEN 1 ELSE 0 END) cancellation_pending,
              SUM(CASE WHEN r.status='COMPLETED' AND r.confirmed_at>=r.submitted_at THEN 1 ELSE 0 END) samples,
              SUM(CASE WHEN r.status='COMPLETED' AND r.confirmed_at>=r.submitted_at THEN TIMESTAMPDIFF(SECOND,r.submitted_at,r.confirmed_at) ELSE 0 END) seconds
            FROM repair_request r
            """+scope+" GROUP BY r.status,r.category").params(p).query((r,n)->new StatsGroup(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6))).list();
        var statuses=new LinkedHashMap<String,Long>();for(String state:STATES)statuses.put(state,0L);
        var categories=new LinkedHashMap<String,Long>();for(String category:List.of("WATER","ELECTRICITY","NETWORK","FURNITURE","DOOR_WINDOW","EQUIPMENT","OTHER"))categories.put(category,0L);
        long total=0,pending=0,samples=0,seconds=0;
        for(var group:groups){total+=group.total();pending+=group.pending();samples+=group.samples();seconds+=group.seconds();statuses.merge(group.status(),group.total(),Long::sum);categories.merge(group.category(),group.total(),Long::sum);}
        Double average=samples==0?null:Math.round(seconds/(samples*3600.0)*100.0)/100.0;
        return new Statistics(from,to,total,statuses.get("COMPLETED"),statuses.get("CANCELLED"),pending,average,samples,
            statuses.entrySet().stream().map(e->new Bucket(e.getKey(),e.getValue())).toList(),categories.entrySet().stream().map(e->new Bucket(e.getKey(),e.getValue())).toList(),LocalDateTime.now(clock));
    }
    private List<Person> qualified(Row r,String capability) {
        return jdbc.sql("""
            SELECT DISTINCT u.id,u.full_name,d.name department_name FROM app_user u
            LEFT JOIN department d ON d.id=u.department_id JOIN repair_staff_scope s ON s.user_id=u.id
            WHERE u.enabled=TRUE AND u.id<>:owner AND s.enabled=TRUE AND s.capability=:ability
            AND s.campus_id=:campus AND s.category=:category AND (s.location_id IS NULL OR s.location_id=:location)
            AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code
                WHERE ur.user_id=u.id AND rp.permission_code=:permission) ORDER BY u.id
            """).param("owner",r.reporter()).param("ability",capability).param("campus",r.campus()).param("category",r.category()).param("location",r.location())
            .param("permission",capability.equals("DISPATCH")?"repair:dispatch":"repair:work").query((s,n)->new Person(s.getLong(1),s.getString(2),s.getString(3))).list();
    }
    private List<Person> dispatchers(Row r) {return qualified(r,"DISPATCH");}
    private void endAssignment(long id) {jdbc.sql("UPDATE repair_assignment SET ended_at=CURRENT_TIMESTAMP WHERE request_id=:id AND ended_at IS NULL").param("id",id).update();}
    private void sendNotices(Row old,Row r,String action) {
        String message=switch(action) {case "submit" -> "报修已提交，等待后勤分诊。";case "return" -> "请查看处理意见，补充资料后重新提交。";case "reject" -> "后勤已拒绝，请查看处理意见。";case "assign","reassign" -> "维修任务已派发，请查看当前处理进度。";case "accept" -> "维修人员已接单，正在处理。";case "progress" -> "维修进展已更新。";case "unable" -> "维修人员反馈无法处理，已交回后勤分诊。";case "complete" -> "维修结果已反馈，请本人核对并验收。";case "reopen" -> "报修人反馈问题未解决，需继续处理。";case "confirm" -> "报修人已确认完成。";case "withdraw" -> "报修人已撤回，记录保留。";default -> null;};
        if(message==null)return;
        notices.write(r.reporter(),"报修处理进度更新","REPAIR",r.id(),"MINE",message);
        if(List.of("submit","unable","reopen","confirm","withdraw").contains(action))for(var p:dispatchers(r))notices.write(p.id(),"报修事务更新","REPAIR",r.id(),"DISPATCH",message);
        if(r.technician()!=null)notices.write(r.technician(),"维修任务更新","REPAIR",r.id(),"WORK",message);
        if(action.equals("reassign") && old.technician()!=null && !Objects.equals(old.technician(),r.technician()))notices.write(old.technician(),"原维修任务已改派","REPAIR",r.id(),"WORK","原任务已交给其他维修人员，办理权限已更新。");
    }
    public Page<History> history(CampusPrincipal user,String view,long id,int page,int size) {
        authorized(user,view,id);pagination(page,size);long total=jdbc.sql("SELECT COUNT(*) FROM repair_action WHERE request_id=:id").param("id",id).query(Long.class).single();
        var items=jdbc.sql("SELECT * FROM repair_action WHERE request_id=:id ORDER BY id DESC LIMIT :limit OFFSET :offset").param("id",id).param("limit",size).param("offset",((long)page-1)*size)
            .query((s,n)->new History(s.getLong("id"),s.getString("actor_name"),s.getString("action"),s.getString("comment"),s.getString("status"),s.getInt("request_version"),s.getString("snapshot"),time(s,"created_at"))).list();return new Page<>(items,total,page,size);
    }
    @Transactional public Detail upload(CampusPrincipal user,String view,long id,Upload input,MultipartFile file) throws java.io.IOException {
        lock();var r=authorized(user,view,id);byte[] bytes=file.getBytes();String type=RepairImages.validate(bytes);
        String name=Objects.toString(file.getOriginalFilename(),"报修图片").replaceAll("[\\\\/\\p{Cntrl}]","_");if(name.length()>160)name=name.substring(0,160);
        String fingerprint=digest(List.of(input.version(),input.purpose(),name,digestBytes(bytes)));
        if(repeat(user,id,"UPLOAD",input.requestKey(),fingerprint)!=null)return detail(user,view,id);
        requireResidency(r);if(r.cancellation()!=null)conflict("取消申请待处理，暂不能更新图片");
        version(r.version(),input.version());int round=input.purpose().equals("RESULT")?r.round()+1:0;
        if(input.purpose().equals("FAULT")){if(!view.equals("mine"))throw invalid("故障图片由报修人维护");editable(r);}
        else if(!view.equals("work") || !r.status().equals("IN_PROGRESS"))conflict("维修图片只能由当前处理中人员上传");
        int limit=input.purpose().equals("FAULT")?6:3;
        if(images(id,input.purpose(),round,true).size()>=limit)throw invalid("当前用途图片已达到数量上限");
        jdbc.sql("INSERT INTO repair_attachment(request_id,uploader_id,purpose,result_round,file_name,file_type,file_bytes) VALUES(:id,:user,:purpose,:round,:name,:type,:bytes)")
            .param("id",id).param("user",user.id()).param("purpose",input.purpose()).param("round",round).param("name",name).param("type",type).param("bytes",bytes).update();
        bump(id);capture(user,id,"UPLOAD",input.requestKey(),fingerprint,"上传"+input.purpose()+"图片");return detail(user,view,id);
    }
    @Transactional public Detail remove(CampusPrincipal user,String view,long id,long image,Action input) {
        lock();var r=authorized(user,view,id);String fingerprint=digest(List.of(image,input));if(repeat(user,id,"REMOVE_IMAGE",input.requestKey(),fingerprint)!=null)return detail(user,view,id);
        requireResidency(r);if(r.cancellation()!=null)conflict("取消申请待处理，暂不能移除图片");
        version(r.version(),input.version());var attachment=jdbc.sql("SELECT purpose,result_round,uploader_id,current FROM repair_attachment WHERE id=:image AND request_id=:id").param("image",image).param("id",id)
            .query((s,n)->new Removal(s.getString(1),s.getInt(2),s.getLong(3),s.getBoolean(4))).optional().orElseThrow(this::missing);
        if(!attachment.current())conflict("图片已从当前资料移除，历史版本仍保留");
        if(attachment.purpose().equals("FAULT")){if(!view.equals("mine"))throw missing();editable(r);}
        else if(!view.equals("work") || !attachment.purpose().equals("RESULT") || attachment.round()!=r.round()+1 || attachment.uploader()!=user.id() || !r.status().equals("IN_PROGRESS"))conflict("只能移除本人尚未提交的结果图片");
        jdbc.sql("UPDATE repair_attachment SET current=FALSE WHERE id=:id").param("id",image).update();bump(id);capture(user,id,"REMOVE_IMAGE",input.requestKey(),fingerprint,"移除当前图片，历史保留");return detail(user,view,id);
    }
    public Attachment download(CampusPrincipal user,String view,long id,long image) {
        authorized(user,view,id);return jdbc.sql("SELECT file_name,file_type,file_bytes FROM repair_attachment WHERE id=:image AND request_id=:id").param("image",image).param("id",id)
            .query((s,n)->new Attachment(s.getString(1),s.getString(2),s.getBytes(3))).optional().orElseThrow(this::missing);
    }
    private List<Image> images(long id,String purpose,Integer round,boolean current) {
        var p=new HashMap<String,Object>();p.put("id",id);String where=" WHERE request_id=:id"+(current?" AND current=TRUE":"");
        if(purpose!=null){where+=" AND purpose=:purpose";p.put("purpose",purpose);}if(round!=null){where+=" AND result_round=:round";p.put("round",round);}
        return jdbc.sql("SELECT id,file_name,file_type,purpose,result_round,created_at FROM repair_attachment"+where+" ORDER BY id").params(p)
            .query((s,n)->new Image(s.getLong(1),s.getString(2),s.getString(3),s.getString(4),s.getInt(5),time(s,"created_at"))).list();
    }
    private List<Image> completionImages(long completion) {
        return jdbc.sql("SELECT a.* FROM repair_attachment a JOIN repair_completion_image ci ON ci.attachment_id=a.id WHERE ci.completion_id=:id ORDER BY a.id").param("id",completion)
            .query((s,n)->new Image(s.getLong("id"),s.getString("file_name"),s.getString("file_type"),s.getString("purpose"),s.getInt("result_round"),time(s,"created_at"))).list();
    }
    private void bump(long id) {jdbc.sql("UPDATE repair_request SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).update();}
    private void capture(CampusPrincipal user,long id,String action,String key,String digest,String comment) {
        var r=find(id);String snapshot=json.writeValueAsString(detail(r,"mine"));
        jdbc.sql("INSERT INTO repair_action(request_id,actor_id,actor_name,action,request_key,input_digest,request_version,status,comment,snapshot) VALUES(:id,:user,:actor,:action,:key,:digest,:version,:status,:comment,:snapshot)")
            .param("id",id).param("user",user.id()).param("actor",actor(user.id())).param("action",action).param("key",key).param("digest",digest).param("version",r.version()).param("status",r.status()).param("comment",comment).param("snapshot",snapshot).update();
    }
    private Long repeat(CampusPrincipal user,Long id,String action,String key,String digest) {
        var previous=jdbc.sql("SELECT request_id,action,input_digest FROM repair_action WHERE actor_id=:user AND request_key=:key").param("user",user.id()).param("key",key)
            .query((r,n)->new Prior(r.getLong(1),r.getString(2),r.getString(3))).optional();
        if(previous.isEmpty())return null;var p=previous.get();
        if((id!=null && id!=p.id()) || !action.equals(p.action()) || !digest.equals(p.digest()))conflict("相同请求标识的内容或操作不一致，请核对原记录");
        return p.id();
    }
    private String digest(Object value) {return digestBytes(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));}
    private String digestBytes(byte[] value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private String actor(long id) {return jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",id).query(String.class).single();}
    private String text(String value) {return value==null?"":value.trim();}
    private void requireResidency(Row r) {if(!r.locationEligible())conflict("原入住关系已结束或住宿权限已变化，请撤回或申请取消旧工单");}
    private void editable(Row r) {if(!List.of("DRAFT","RETURNED").contains(r.status()))conflict("只有草稿或待补充资料可以编辑");}
    private void pagination(int page,int size) {if(page<1 || page>1000000 || size<1 || size>100)throw invalid("分页参数无效");}
    private void version(int actual,int expected) {if(actual!=expected)stale();}
    private void stale() {conflict("资料、状态或人员分配已变化，请刷新核对后操作");}
    private void conflict(String message) {throw new ApiException(HttpStatus.CONFLICT,"REPAIR_CONFLICT",message);}
    private ApiException invalid(String message) {return new ApiException(HttpStatus.BAD_REQUEST,"REPAIR_INVALID",message);}
    private ApiException missing() {return new ApiException(HttpStatus.NOT_FOUND,"REPAIR_NOT_FOUND","报修记录或图片不存在，或当前没有访问权限");}
    private LocalDateTime time(ResultSet r,String name) throws SQLException {Timestamp t=r.getTimestamp(name);return t==null?null:t.toLocalDateTime();}
    private Item item(Row r) {return new Item(r.id(),r.reporterName(),r.title(),r.campus(),r.campusName(),r.location(),r.locationName(),r.category(),r.priority(),r.status(),r.technicianName(),r.version(),r.updated(),r.cancellation()!=null,overdue(r));}
    private Row row(ResultSet r,int n) throws SQLException {return new Row(r.getLong("id"),r.getLong("reporter_id"),r.getString("reporter_name"),r.getObject("campus_id",Long.class),r.getString("campus_name"),r.getObject("location_id",Long.class),r.getString("location_name"),r.getString("category"),r.getString("title"),r.getString("description"),r.getString("location_detail"),r.getString("contact"),r.getString("contact_window"),r.getString("priority"),r.getString("status"),r.getObject("technician_id",Long.class),r.getString("technician_name"),r.getInt("completion_round"),r.getString("processing_comment"),r.getInt("version"),time(r,"submitted_at"),time(r,"confirmed_at"),time(r,"created_at"),time(r,"updated_at"),r.getObject("current_cancellation_id",Long.class),time(r,"result_at"),r.getBoolean("location_eligible"));}
    private record Row(long id,long reporter,String reporterName,Long campus,String campusName,Long location,String locationName,String category,String title,String description,String locationDetail,String contact,String contactWindow,String priority,String status,Long technician,String technicianName,int round,String comment,int version,LocalDateTime submitted,LocalDateTime confirmed,LocalDateTime created,LocalDateTime updated,Long cancellation,LocalDateTime resultAt,boolean locationEligible) {}
    private record StatsGroup(String status,String category,long total,long pending,long samples,long seconds) {}
    private record Prior(long id,String action,String digest) {}
    private record Person(long id,String name,String department) {}
    private record Removal(String purpose,int round,long uploader,boolean current) {}
}
