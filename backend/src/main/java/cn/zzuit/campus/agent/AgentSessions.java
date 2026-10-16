package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentSessionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.certificate.CertificateModels;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveModels;
import cn.zzuit.campus.leave.LeaveService;
import cn.zzuit.campus.housing.HousingTransferService;
import cn.zzuit.campus.housing.HousingTransferModels;
import cn.zzuit.campus.repair.RepairModels;
import cn.zzuit.campus.repair.RepairService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentSessions {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final AgentService agent;
    private final AgentLeaveDrafts leaveDrafts;
    private final AgentCertificateDrafts certificateDrafts;
    private final AgentRepairDrafts repairDrafts;
    private final AgentHousingDrafts housingDrafts;
    private final HousingTransferService housingTransfers;
    private final LeaveService leaves;
    private final CertificateService certificates;
    private final RepairService repairs;
    private final TransactionTemplate transaction;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Set<Long> operations=ConcurrentHashMap.newKeySet();
    private final Map<Long,Long> activeTurns=new ConcurrentHashMap<>();
    private record Task(long id,long sessionId,String businessKey,String kind,String fields,String stage,Long businessId,int version,LocalDateTime updatedAt) {}
    public AgentSessions(JdbcClient jdbc,IdentityService identities,AgentService agent,AgentLeaveDrafts leaveDrafts,AgentCertificateDrafts certificateDrafts,AgentRepairDrafts repairDrafts,LeaveService leaves,CertificateService certificates,RepairService repairs,AgentHousingDrafts housingDrafts,HousingTransferService housingTransfers,PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.identities=identities; this.agent=agent; this.leaveDrafts=leaveDrafts; this.certificateDrafts=certificateDrafts; this.repairDrafts=repairDrafts; this.leaves=leaves; this.certificates=certificates; this.repairs=repairs; this.housingDrafts=housingDrafts; this.housingTransfers=housingTransfers; transaction=new TransactionTemplate(manager);
    }
    private void require(CampusPrincipal user) { identities.requirePermission(user,"agent:query"); }
    private void kind(CampusPrincipal user,String kind) { switch(kind) { case "LEAVE" -> leaveDrafts.require(user); case "CERTIFICATE" -> certificateDrafts.require(user); case "REPAIR" -> repairDrafts.require(user); case "HOUSING" -> housingDrafts.require(user); default -> throw missing(); } }
    private String permission(String kind) { return switch(kind) { case "LEAVE" -> "leave:apply"; case "CERTIFICATE" -> "certificate:apply"; case "REPAIR" -> "repair:apply"; case "HOUSING" -> "housing:read"; default -> ""; }; }
    private String taskTitle(String kind) { return switch(kind) { case "LEAVE" -> "准备请假"; case "CERTIFICATE" -> "准备在校证明"; case "REPAIR" -> "准备报修"; case "HOUSING" -> "准备调宿"; default -> "准备事项"; }; }
    private void lock() { jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update(); }
    private <T> T write(Supplier<T> action) { return transaction.execute(status->{ lock(); return action.get(); }); }
    private void touch(long id,String title) { jdbc.sql("UPDATE agent_session SET updated_at=CURRENT_TIMESTAMP,title=CASE WHEN title='新对话' THEN :title ELSE title END WHERE id=:id").param("title",title.length()>60?title.substring(0,60):title).param("id",id).update(); }
    public Session session(CampusPrincipal user,long id) {
        require(user); return jdbc.sql("SELECT * FROM agent_session WHERE id=:id AND user_id=:u").param("id",id).param("u",user.id()).query(this::sessionRow).optional().orElseThrow(this::missing);
    }
    public Page<Session> list(CampusPrincipal user,int page,int size) {
        require(user); pagination(page,size);
        long total=jdbc.sql("SELECT COUNT(*) FROM agent_session WHERE user_id=:u").param("u",user.id()).query(Long.class).single();
        var items=jdbc.sql("SELECT * FROM agent_session WHERE user_id=:u ORDER BY updated_at DESC,id DESC LIMIT :limit OFFSET :offset").param("u",user.id()).param("limit",size).param("offset",((long)page-1)*size).query(this::sessionRow).list();
        return new Page<>(items,total,page,size);
    }
    public Session create(CampusPrincipal user,CreateSession input) {
        return write(()->{
            require(user); var prior=jdbc.sql("SELECT * FROM agent_session WHERE user_id=:u AND request_key=:key").param("u",user.id()).param("key",input.requestKey()).query(this::sessionRow).optional(); if(prior.isPresent()) return prior.get();
            var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO agent_session(user_id,request_key) VALUES(:u,:key)").param("u",user.id()).param("key",input.requestKey()).update(key,"id"); return session(user,key.getKey().longValue());
        });
    }
    public SessionDetail detail(CampusPrincipal user,long id,Long before) {
        var owner=session(user,id); if(before!=null && before<=0) throw bad("历史游标无效");
        var turns=new ArrayList<>(jdbc.sql("SELECT * FROM agent_turn WHERE session_id=:id AND id<:before ORDER BY id DESC LIMIT 21").param("id",id).param("before",before==null?Long.MAX_VALUE:before).query((r,n)->turnRow(r,user)).list());
        boolean older=turns.size()>20; if(older) turns.removeLast(); Collections.reverse(turns);
        var permissions=identities.permissions(user.id());
        var tasks=jdbc.sql("SELECT id,kind,stage,version,updated_at FROM agent_task WHERE session_id=:id ORDER BY id DESC").param("id",id)
            .query((r,n)->new TaskSummary(r.getLong("id"),r.getString("kind"),r.getString("stage"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime())).list().stream()
            .filter(t->permissions.contains(permission(t.kind()))).limit(50).toList();
        require(user); return new SessionDetail(owner,List.copyOf(turns),tasks,older);
    }
    public QueryResult query(CampusPrincipal user,long id,SessionQuery input) {
        session(user,id);
        if(!operations.add(user.id())) throw running();
        Long turnId=null;
        try {
            var query=input.input();
            var prior=jdbc.sql("SELECT * FROM agent_turn WHERE session_id=:id AND request_key=:key").param("id",id).param("key",input.requestKey()).query((r,n)->turnRow(r,user)).optional();
            if(prior.isPresent()) {
                var old=prior.get(); if(!old.question().equals(query.message().trim()) || old.mode()!=query.mode() || !Objects.equals(old.context(),query.context())) throw conflict("重复请求与原问题不同，请新建一次查询");
                return new QueryResult(old,null,true);
            }
            turnId=write(()->{
                session(user,id); var key=new GeneratedKeyHolder();
                jdbc.sql("INSERT INTO agent_turn(session_id,request_key,question,mode,context_type,context_id,status) VALUES(:id,:key,:question,:mode,:type,:context,'RUNNING')")
                    .param("id",id).param("key",input.requestKey()).param("question",query.message().trim()).param("mode",query.mode().name())
                    .param("type",query.context()==null?null:query.context().type()).param("context",query.context()==null?null:query.context().id()).update(key,"id");
                touch(id,query.message().trim()); return key.getKey().longValue();
            });
            activeTurns.put(user.id(),turnId);
            var answer=agent.query(user,query); long completeId=turnId;
            return write(()->{ session(user,id); jdbc.sql("UPDATE agent_turn SET status='SUCCESS',run_id=:run WHERE id=:id").param("run",answer.runId()).param("id",completeId).update(); touch(id,query.message().trim()); return new QueryResult(turn(user,completeId),answer,false); });
        } catch(RuntimeException ex) {
            if(turnId!=null) { long failedId=turnId; write(()->jdbc.sql("UPDATE agent_turn SET status='FAILED' WHERE id=:id").param("id",failedId).update()); }
            throw ex;
        } finally { activeTurns.remove(user.id()); operations.remove(user.id()); }
    }
    private Turn turn(CampusPrincipal user,long id) { return jdbc.sql("SELECT * FROM agent_turn WHERE id=:id").param("id",id).query((r,n)->turnRow(r,user)).single(); }
    public TaskView createTask(CampusPrincipal user,long sessionId,CreateTask input) {
        return write(()->{
            session(user,sessionId); kind(user,input.kind());
            var prior=jdbc.sql("SELECT * FROM agent_task WHERE session_id=:id AND request_key=:key").param("id",sessionId).param("key",input.requestKey()).query(this::taskRow).optional();
            if(prior.isPresent()) { if(!prior.get().kind().equals(input.kind())) throw conflict("任务类型与原请求不同"); return view(user,prior.get()); }
            long count=jdbc.sql("SELECT COUNT(*) FROM agent_task WHERE session_id=:id").param("id",sessionId).query(Long.class).single(); if(count>=50) throw conflict("本次对话已有 50 项准备任务，请新建对话");
            String fields=switch(input.kind()) { case "LEAVE" -> encode(new LeaveFields("","","","","","","")); case "CERTIFICATE" -> encode(new CertificateFields("")); case "HOUSING" -> encode(new HousingFields("","","")); default -> encode(new RepairFields("","","",null,"","","")); };
            var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO agent_task(session_id,request_key,business_key,kind,fields_json) VALUES(:id,:key,:business,:kind,:fields)")
                .param("id",sessionId).param("key",input.requestKey()).param("business","agent-task-"+UUID.randomUUID()).param("kind",input.kind()).param("fields",fields).update(key,"id");
            touch(sessionId,taskTitle(input.kind())); return task(user,sessionId,key.getKey().longValue());
        });
    }
    public TaskView task(CampusPrincipal user,long sessionId,long taskId) { return view(user,ownedTask(user,sessionId,taskId)); }
    private Task ownedTask(CampusPrincipal user,long sessionId,long taskId) {
        session(user,sessionId); var task=jdbc.sql("SELECT * FROM agent_task WHERE session_id=:s AND id=:id").param("s",sessionId).param("id",taskId).query(this::taskRow).optional().orElseThrow(this::missing);
        kind(user,task.kind()); return task;
    }
    public TaskView prepareLeave(CampusPrincipal user,long sessionId,long id,TaskLeave input) {
        var task=ownedTask(user,sessionId,id); editable(task,input.version(),"LEAVE");
        if(!operations.add(user.id())) throw running();
        try { var preview=agent.prepareLeave(user,input.input()); return update(user,sessionId,id,input.version(),"LEAVE",encode(preview.fields())); }
        finally { operations.remove(user.id()); }
    }
    public TaskView prepareCertificate(CampusPrincipal user,long sessionId,long id,TaskCertificate input) {
        var task=ownedTask(user,sessionId,id); editable(task,input.version(),"CERTIFICATE");
        if(!operations.add(user.id())) throw running();
        try { var preview=agent.prepareCertificate(user,input.input()); return update(user,sessionId,id,input.version(),"CERTIFICATE",encode(preview.fields())); }
        finally { operations.remove(user.id()); }
    }
    public TaskView prepareRepair(CampusPrincipal user,long sessionId,long id,TaskRepair input) {
        var task=ownedTask(user,sessionId,id); editable(task,input.version(),"REPAIR");
        if(!operations.add(user.id())) throw running();
        try { var preview=agent.prepareRepair(user,input.input()); return update(user,sessionId,id,input.version(),"REPAIR",encode(preview.fields())); }
        finally { operations.remove(user.id()); }
    }
    public TaskView prepareHousing(CampusPrincipal user,long sessionId,long id,TaskHousing input) {
        var task=ownedTask(user,sessionId,id); editable(task,input.version(),"HOUSING");
        if(!operations.add(user.id()))throw running();
        try {var preview=agent.prepareHousing(user,input.input());return update(user,sessionId,id,input.version(),"HOUSING",encode(preview.fields()));}
        finally {operations.remove(user.id());}
    }
    private TaskView update(CampusPrincipal user,long sessionId,long id,int version,String kind,String fields) {
        return write(()->{ editable(ownedTask(user,sessionId,id),version,kind); jdbc.sql("UPDATE agent_task SET fields_json=:f,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("f",fields).param("id",id).update(); touch(sessionId,taskTitle(kind)); return task(user,sessionId,id); });
    }
    public TaskView save(CampusPrincipal user,long sessionId,long id,ConfirmTask input) {
        return write(()->{
            var task=ownedTask(user,sessionId,id);
            if(!Boolean.TRUE.equals(input.confirmed())) throw bad("请亲自核对并确认保存草稿");
            if(task.stage().equals("LINKED")) return view(user,task);
            editable(task,input.version(),task.kind()); var checked=view(user,task); long businessId;
            if(task.kind().equals("LEAVE")) {
                var preview=checked.leavePreview(); if(!preview.ready()) throw conflict("请假信息或规则已变化，请重新核对预览"); var f=preview.fields();
                businessId=leaves.save(user,null,new LeaveModels.Draft(task.businessKey(),f.reasonType(),f.reason(),f.contact(),LocalDateTime.parse(f.startDate()+"T"+f.startTime()+":00"),LocalDateTime.parse(f.endDate()+"T"+f.endTime()+":00"),0)).id();
            } else if(task.kind().equals("CERTIFICATE")) {
                var preview=checked.certificatePreview(); if(!preview.ready()) throw conflict("证明信息或规则已变化，请重新核对预览");
                if(input.expectedIdentity()==null || !input.expectedIdentity().equals(preview.identity())) throw conflict("登记资料已变化，请重新核对本人资料并确认");
                businessId=certificates.save(user,null,new CertificateModels.Draft(task.businessKey(),preview.fields().purpose(),"DIGITAL",true,0,input.expectedIdentity())).id();
            } else if(task.kind().equals("HOUSING")) {
                var preview=checked.housingPreview();
                if(preview==null || !preview.ready())throw conflict("当前住宿、申请或规则已变化，请重新核对调宿预览");
                if(input.expectedStayToken()==null || !input.expectedStayToken().equals(preview.stayToken()))throw conflict("当前住宿或办理指南已变化，请重新核对后确认");
                var f=preview.fields();businessId=housingTransfers.save(user,null,new HousingTransferModels.DraftInput(task.businessKey(),0,f.reason(),f.preferredArea(),f.materialNote())).id();
            } else {
                var preview=checked.repairPreview(); if(preview==null || !preview.ready()) throw conflict("报修信息或开放地点已变化，请重新核对预览"); var f=preview.fields();
                businessId=repairs.save(user,null,new RepairModels.Draft(task.businessKey(),0,f.locationId(),f.category(),f.title(),f.description(),f.locationDetail(),f.contact(),f.contactWindow())).id();
            }
            jdbc.sql("UPDATE agent_task SET stage='LINKED',business_id=:b,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("b",businessId).param("id",id).update(); touch(sessionId,taskTitle(task.kind())); return task(user,sessionId,id);
        });
    }
    private TaskView view(CampusPrincipal user,Task task) {
        kind(user,task.kind()); LeavePreview leave=null; CertificatePreview certificate=null; RepairPreview repair=null; HousingPreview housing=null; Business business=null;
        if(task.businessId()!=null) {
            if(task.kind().equals("LEAVE")) { var r=leaves.detail(user,"mine",task.businessId()); business=new Business(r.id(),"LEAVE",r.status(),r.version(),"/portal/leave?id="+r.id()+"&tab=details"); }
            else if(task.kind().equals("CERTIFICATE")) { var r=certificates.detail(user,"mine",task.businessId()); business=new Business(r.id(),"CERTIFICATE",r.status(),r.version(),"/portal/certificates?id="+r.id()+"&tab=details"); }
            else if(task.kind().equals("HOUSING")) {var r=housingTransfers.mineDetail(user,task.businessId());business=new Business(r.id(),"HOUSING",r.status(),r.version(),"/housing/transfer?id="+r.id()+"&tab=details");}
            else { var r=repairs.detail(user,"mine",task.businessId()); business=new Business(r.id(),"REPAIR",r.status(),r.version(),"/repairs/mine?id="+r.id()+"&tab=details"); }
        } else if(task.kind().equals("LEAVE")) leave=leaveDrafts.preview(user,"restored-"+task.id(),Mode.LOCAL,decode(task.fields(),LeaveFields.class));
        else if(task.kind().equals("CERTIFICATE")) certificate=certificateDrafts.preview(user,"restored-"+task.id(),Mode.LOCAL,decode(task.fields(),CertificateFields.class));
        else if(task.kind().equals("HOUSING")) housing=housingDrafts.preview(user,"restored-"+task.id(),Mode.LOCAL,decode(task.fields(),HousingFields.class));
        else repair=repairDrafts.preview(user,"restored-"+task.id(),Mode.LOCAL,decode(task.fields(),RepairFields.class));
        return new TaskView(task.id(),task.kind(),task.stage(),task.version(),leave,certificate,repair,business,task.updatedAt(),housing);
    }
    private void editable(Task task,int version,String kind) { if(!task.kind().equals(kind)) throw missing(); if(!task.stage().equals("PREVIEW")) throw conflict("任务已关联业务申请，请进入原申请办理或新建准备任务"); if(task.version()!=version) throw conflict("任务进度已在其他窗口更新，请重新恢复后继续"); }
    private Session sessionRow(ResultSet r,int n) throws SQLException { return new Session(r.getLong("id"),r.getString("title"),r.getTimestamp("created_at").toLocalDateTime(),r.getTimestamp("updated_at").toLocalDateTime()); }
    private Turn turnRow(ResultSet r,CampusPrincipal user) throws SQLException {
        String type=r.getString("context_type"),status=r.getString("status"); if(status.equals("RUNNING") && !Objects.equals(activeTurns.get(user.id()),r.getLong("id"))) status="INTERRUPTED";
        return new Turn(r.getLong("id"),r.getString("question"),Mode.valueOf(r.getString("mode")),type==null?null:new Context(type,r.getLong("context_id")),status,r.getString("run_id"),r.getTimestamp("created_at").toLocalDateTime());
    }
    private Task taskRow(ResultSet r,int n) throws SQLException { long business=r.getLong("business_id"); Long businessId=r.wasNull()?null:business; return new Task(r.getLong("id"),r.getLong("session_id"),r.getString("business_key"),r.getString("kind"),r.getString("fields_json"),r.getString("stage"),businessId,r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime()); }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private <T> T decode(String value,Class<T> type) { return json.readValue(value,type); }
    private void pagination(int page,int size) { if(page<1 || page>100000 || size<1 || size>20) throw bad("分页参数无效"); }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","对话或准备任务不存在，或当前账号不可访问"); }
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"SESSION_INPUT_INVALID",message); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"SESSION_CHANGED",message); }
    private ApiException running() { return new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RUNNING","上一项操作仍在进行，请等待结果"); }
}
