package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Persisted, leased observe/decide/act loop. No transaction is held during a model request. */
@Service
public class AgentMissionService {
    private static final int TOTAL_STEPS=24,STEPS_PER_REQUEST=6;
    private static final Duration REQUEST_BUDGET=Duration.ofSeconds(60),LEASE=Duration.ofSeconds(90);
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final AgentSessions sessions;
    private final AgentMissionTools tools;
    private final DeepSeekClient model;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    private record Row(long id,long session,long owner,String goal,Mode mode,boolean consent,String stage,State state,String businessKey,int count,int version,String token,LocalDateTime lease,String lastKey,String lastHash,LocalDateTime updated) {}
    private record Claim(Row row,boolean execute) {}
    public AgentMissionService(JdbcClient jdbc,IdentityService identities,AgentSessions sessions,AgentMissionTools tools,DeepSeekClient model,TransactionTemplate tx) {
        this.jdbc=jdbc;this.identities=identities;this.sessions=sessions;this.tools=tools;this.model=model;this.tx=tx;
    }
    private LocalDateTime now(){return AgentMissionTools.now();}
    private <T>T write(Supplier<T> action){return tx.execute(ignored->{jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();return action.get();});}
    private void require(CampusPrincipal user,long session){sessions.session(user,session);}
    private void requireKind(CampusPrincipal user,String kind){if(kind.equals("CERTIFICATE"))identities.requirePermission(user,"certificate:apply");else if(kind.equals("LEAVE"))identities.requirePermission(user,"leave:apply");else if(kind.equals("REPAIR"))identities.requirePermission(user,"repair:apply");else{identities.requirePermission(user,"reservation:apply");identities.requirePermission(user,"portal:read");}}
    private String resolveKind(Create input){
        if(input.kind()!=null&&!input.kind().equals("AUTO"))return input.kind();
        boolean repair=input.goal().matches("(?s).*(报修|故障|维修).*"),reservation=input.goal().matches("(?s).*(预约|借用|场地|教室|会议室|报告厅|研讨室).*");
        boolean leave=input.goal().matches("(?s).*(请假|休假).*"),explicitReservation=input.goal().matches("(?s).*(预约|借用).*");
        boolean certificate=input.goal().matches("(?s).*(在校证明|学籍证明|证明草稿|开证明|办证明).*");
        if((certificate&&(leave||repair||explicitReservation))||(leave&&repair)||(leave&&explicitReservation)||(repair&&reservation&&explicitReservation))throw error(HttpStatus.BAD_REQUEST,"MISSION_KIND_REQUIRED","目标同时涉及多种业务，请明确选择一种任务，分别办理");
        if(certificate)return "CERTIFICATE";if(leave)return "LEAVE";if(repair)return "REPAIR";if(reservation)return "RESERVATION";
        throw error(HttpStatus.BAD_REQUEST,"MISSION_SCOPE","请描述在校证明、请假、报修或场地预约目标，或明确选择任务类型");
    }
    private Row row(ResultSet r,int n)throws SQLException {
        return new Row(r.getLong("id"),r.getLong("session_id"),r.getLong("user_id"),r.getString("goal"),Mode.valueOf(r.getString("mode")),r.getBoolean("external_consent"),r.getString("stage"),json.readValue(r.getString("state_json"),State.class),r.getString("business_key"),r.getInt("step_count"),r.getInt("version"),r.getString("runner_token"),r.getObject("lease_until",LocalDateTime.class),r.getString("last_request_key"),r.getString("last_request_hash"),r.getObject("updated_at",LocalDateTime.class));
    }
    private Row load(CampusPrincipal user,long session,long id){require(user,session);var r=jdbc.sql("SELECT * FROM agent_mission WHERE id=:id AND session_id=:s AND user_id=:u").param("id",id).param("s",session).param("u",user.id()).query(this::row).optional().orElseThrow(this::missing);requireKind(user,r.state().kind);return r;}
    private boolean running(Row r){return r.stage().equals("RUNNING")&&r.lease()!=null&&r.lease().isAfter(now());}
    public List<Summary> list(CampusPrincipal user,long session){require(user,session);return jdbc.sql("SELECT id,request_key,goal,mode,stage,version,updated_at,lease_until,kind FROM agent_mission WHERE session_id=:s AND user_id=:u ORDER BY id DESC LIMIT 10").param("s",session).param("u",user.id()).query((r,n)->new Summary(r.getLong(1),r.getString(2),r.getString(3),Mode.valueOf(r.getString(4)),r.getString(5).equals("RUNNING")&&(r.getObject(8,LocalDateTime.class)==null||r.getObject(8,LocalDateTime.class).isBefore(now()))?"INTERRUPTED":r.getString(5),r.getInt(6),r.getObject(7,LocalDateTime.class),r.getString(9))).list().stream().filter(s->s.kind().equals("CERTIFICATE")?identities.permissions(user.id()).contains("certificate:apply"):s.kind().equals("LEAVE")?identities.permissions(user.id()).contains("leave:apply"):s.kind().equals("REPAIR")?identities.permissions(user.id()).contains("repair:apply"):identities.permissions(user.id()).containsAll(List.of("reservation:apply","portal:read"))).toList();}
    public View detail(CampusPrincipal user,long session,long id){return view(user,load(user,session,id));}
    private List<Step> steps(long id){return jdbc.sql("SELECT * FROM agent_mission_step WHERE mission_id=:id ORDER BY step_number").param("id",id).query((r,n)->new Step(r.getInt("step_number"),r.getString("action"),r.getString("status"),r.getString("message"),r.getString("observation_json")==null?null:json.readValue(r.getString("observation_json"),Observation.class),r.getObject("prompt_tokens",Integer.class),r.getObject("completion_tokens",Integer.class),r.getObject("created_at",LocalDateTime.class))).list();}
    private View view(CampusPrincipal user,Row r){
        String stage=r.stage().equals("RUNNING")&&!running(r)?"INTERRUPTED":r.stage();var s=r.state();
        return new View(r.id(),r.session(),r.goal(),r.mode(),stage,r.version(),s.fields,s.missing,s.options,s.selectedKey,s.proposalHash,s.conflicts,s.message,steps(r.id()),tools.business(user,s),r.count()<TOTAL_STEPS&&Set.of("PAUSED","FAILED","INTERRUPTED").contains(stage),r.updated(),s.kind,s.repairFields,s.repairPreview,tools.repairLocations(user,s),s.leaveFields,s.leavePreview,s.certificateFields,s.certificatePreview);
    }
    private void touch(long session,String goal){jdbc.sql("UPDATE agent_session SET updated_at=CURRENT_TIMESTAMP,title=CASE WHEN title='新对话' THEN :title ELSE title END WHERE id=:id").param("title",("Agent任务："+goal).substring(0,Math.min(60,goal.length()+8))).param("id",session).update();}
    private void consent(Mode mode,boolean agreed){if(mode==Mode.DEEPSEEK&&!agreed)throw error(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次目标和后续脱敏执行观察发送至DeepSeek，或选择本地任务流程");if(mode==Mode.DEEPSEEK&&!model.ready())throw error(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek尚未连接，可选择本地任务流程");}
    public View create(CampusPrincipal user,long session,Create input){
        require(user,session);String kind=resolveKind(input);requireKind(user,kind);String hash=tools.hash(input);
        Claim created=write(()->{
            require(user,session);var prior=jdbc.sql("SELECT * FROM agent_mission WHERE session_id=:s AND request_key=:k").param("s",session).param("k",input.requestKey()).query(this::row).optional();
            if(prior.isPresent()){String oldHash=jdbc.sql("SELECT create_hash FROM agent_mission WHERE id=:id").param("id",prior.get().id()).query(String.class).single();if(!oldHash.equals(hash))throw conflict("重复任务请求与原目标不同，请新建任务");return new Claim(prior.get(),false);}
            consent(input.mode(),input.externalConsent());
            if(jdbc.sql("SELECT COUNT(*) FROM agent_mission WHERE session_id=:s").param("s",session).query(Long.class).single()>=10)throw conflict("当前对话已有10个任务，请新建对话");
            var state=new State();state.kind=kind;state.fields=!kind.equals("RESERVATION")?tools.normalize(null):tools.initial(input.goal(),input.fields());state.repairFields=kind.equals("REPAIR")?tools.normalizeRepair(input.repairFields()):null;state.leaveFields=kind.equals("LEAVE")?tools.normalizeLeave(input.leaveFields()):null;state.certificateFields=kind.equals("CERTIFICATE")?tools.normalizeCertificate(input.certificateFields()):null;state.message="正在规划并执行任务目标。";String token=UUID.randomUUID().toString();var key=new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO agent_mission(session_id,user_id,request_key,create_hash,goal,mode,external_consent,stage,state_json,business_key,runner_token,lease_until,kind) VALUES(:s,:u,:key,:hash,:goal,:mode,:consent,'RUNNING',:state,:business,:token,:lease,:kind)")
                .param("s",session).param("u",user.id()).param("key",input.requestKey()).param("hash",hash).param("goal",input.goal().trim()).param("mode",input.mode().name()).param("consent",input.externalConsent()).param("state",json.writeValueAsString(state)).param("business","agent-mission-"+UUID.randomUUID()).param("token",token).param("lease",now().plus(LEASE)).param("kind",kind).update(key,"id");
            touch(session,input.goal());return new Claim(load(user,session,key.getKey().longValue()),true);
        });
        // Only the creator of this new lease executes it. Replayed creates return the stored state.
        var row=created.row();return created.execute()?run(user,row.session(),row.id(),row.token()):view(user,row);
    }
    private Row persist(Row r,State state,String stage,String token){
        if(jdbc.sql("UPDATE agent_mission SET state_json=:state,stage=:stage,runner_token=:token,lease_until=:lease,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
            .param("state",json.writeValueAsString(state)).param("stage",stage).param("token",token).param("lease",token==null?null:now().plus(LEASE)).param("id",r.id()).param("v",r.version()).update()!=1)throw conflict("任务状态已变化，请刷新");
        return jdbc.sql("SELECT * FROM agent_mission WHERE id=:id").param("id",r.id()).query(this::row).single();
    }
    public View action(CampusPrincipal user,long session,long id,Action input){
        Claim claimed=write(()->{
            var r=load(user,session,id);String hash=tools.hash(input);
            if(input.requestKey().equals(r.lastKey())){if(!hash.equals(r.lastHash()))throw conflict("重复操作请求与原内容不同");return new Claim(r,false);}
            if(input.action().equals("CANCEL")){
                if(Set.of("DONE","CANCELLED").contains(r.stage()))return new Claim(r,false);var s=r.state();s.message="已取消后续任务动作；已保存的业务草稿仍保留，可在原页面处理。";
                jdbc.sql("UPDATE agent_mission_step SET status='CANCELLED',message='已取消后续任务动作' WHERE mission_id=:id AND status='RUNNING'").param("id",id).update();
                remember(r,input,hash);return new Claim(persist(r,s,"CANCELLED",null),false);
            }
            if(input.version()!=r.version())throw conflict("任务版本已变化，请刷新后核对");
            if(running(r))throw conflict("任务正在执行，请等待或取消");
            if(Set.of("DONE","CANCELLED").contains(r.stage()))throw conflict("任务已结束，请查看原业务记录或新建任务");
            var s=r.state();
            switch(input.action()) {
                case "REVISE" -> {
                    if(s.draftId!=null)throw conflict("已有业务草稿，请在原页面修改");
                    if(tools.certificate(s)){if(input.certificateFields()==null)throw error(HttpStatus.BAD_REQUEST,"MISSION_FIELDS_REQUIRED","请提供实际证明用途");tools.invalidate(s,"用途已更新，正在重新核对登记资料。");s.certificateFields=tools.normalizeCertificate(input.certificateFields());}
                    else if(tools.leave(s)){if(input.leaveFields()==null)throw error(HttpStatus.BAD_REQUEST,"MISSION_FIELDS_REQUIRED","请提供完整请假条件");tools.invalidate(s,"资料已更新，正在重新核对。");s.leaveFields=tools.normalizeLeave(input.leaveFields());}
                    else if(tools.repair(s)){if(input.repairFields()==null)throw error(HttpStatus.BAD_REQUEST,"MISSION_FIELDS_REQUIRED","请提供完整报修条件");tools.invalidate(s,"资料已更新，正在重新核对。");s.repairFields=tools.normalizeRepair(input.repairFields());}
                    else{if(input.fields()==null)throw error(HttpStatus.BAD_REQUEST,"MISSION_FIELDS_REQUIRED","请提供完整任务条件");var fields=tools.normalize(input.fields());if(!tools.sameSearch(s.fields,fields))tools.invalidate(s,"条件已更新，正在重新规划。");s.fields=fields;}
                    s.proposalReady=false;s.proposalHash=null;s.confirmed=false;s.missing=List.of();
                }
                case "SELECT" -> {
                    if(!r.stage().equals("WAITING_SELECTION")||input.optionKey()==null||s.options.stream().noneMatch(o->o.key().equals(input.optionKey())))throw conflict("请选择当前候选列表中的场地");
                    s.selectedKey=input.optionKey();s.proposalReady=false;s.confirmed=false;s.proposalHash=null;
                }
                case "CONFIRM" -> {
                    if(!Boolean.TRUE.equals(input.confirmed()))throw error(HttpStatus.BAD_REQUEST,"MISSION_CONFIRM_REQUIRED","请亲自核对并明确确认保存本人草稿");
                    if(!r.stage().equals("WAITING_CONFIRMATION")||!s.proposalReady||!Objects.equals(input.proposalHash(),s.proposalHash))throw conflict("确认预览已失效，请重新核对");s.confirmed=true;
                }
                case "CONTINUE" -> {if(!Set.of("PAUSED","FAILED","RUNNING").contains(r.stage()))throw conflict("当前任务需要补充条件、选择候选或确认预览");}
                default -> throw error(HttpStatus.BAD_REQUEST,"MISSION_ACTION","任务操作无效");
            }
            if(r.count()>=TOTAL_STEPS){s.message="任务已达到24步上限，请核对记录后新建任务。";return new Claim(persist(r,s,"FAILED",null),false);}
            consent(r.mode(),r.consent());remember(r,input,hash);String token=UUID.randomUUID().toString();
            jdbc.sql("UPDATE agent_mission_step SET status='INTERRUPTED',message='上轮执行已中断，保留记录并重新规划' WHERE mission_id=:id AND status='RUNNING'").param("id",id).update();
            return new Claim(persist(r,s,"RUNNING",token),true);
        });
        // A successful claim changes version exactly once; duplicate request keys never re-enter the loop.
        return claimed.execute()?run(user,session,id,claimed.row().token()):view(user,claimed.row());
    }
    private void remember(Row r,Action input,String hash){jdbc.sql("UPDATE agent_mission SET last_request_key=:key,last_request_hash=:hash WHERE id=:id").param("key",input.requestKey()).param("hash",hash).param("id",r.id()).update();}
    private Row lease(CampusPrincipal user,long session,long id,String token){var r=load(user,session,id);if(!r.stage().equals("RUNNING")||!Objects.equals(token,r.token()))throw conflict("任务执行权已变化，请刷新当前状态");return r;}
    private void completeStep(long id,int step,String action,String status,String message,Observation observation,Plan plan){
        jdbc.sql("UPDATE agent_mission_step SET action=:action,status=:status,message=:message,observation_json=:obs,prompt_tokens=:prompt,completion_tokens=:completion WHERE mission_id=:id AND step_number=:step")
            .param("action",action).param("status",status).param("message",message.length()>500?message.substring(0,500):message).param("obs",json.writeValueAsString(observation)).param("prompt",plan==null?null:plan.promptTokens()).param("completion",plan==null?null:plan.completionTokens()).param("id",id).param("step",step).update();
    }
    private View run(CampusPrincipal user,long session,long id,String token){
        long deadline=System.nanoTime()+REQUEST_BUDGET.toNanos();
        for(int iteration=0;iteration<STEPS_PER_REQUEST&&System.nanoTime()<deadline;iteration++) {
            var before=load(user,session,id);if(!Objects.equals(before.token(),token)||!before.stage().equals("RUNNING"))return view(user,before);
            if(before.count()>=TOTAL_STEPS)return view(user,write(()->{var r=lease(user,session,id,token);r.state().message="任务已达到24步上限，请新建任务。";return persist(r,r.state(),"FAILED",null);}));
            var allowed=tools.allowed(before.state());
            if(allowed.isEmpty())return view(user,write(()->{var r=lease(user,session,id,token);return persist(r,r.state(),"WAITING_CONFIRMATION",null);}));
            int number=write(()->{
                var r=lease(user,session,id,token);int next=r.count()+1;
                jdbc.sql("INSERT INTO agent_mission_step(mission_id,step_number,action,status,message) VALUES(:id,:step,'planning','RUNNING','正在根据执行观察规划下一步')").param("id",id).param("step",next).update();
                jdbc.sql("UPDATE agent_mission SET step_count=:n,lease_until=:lease WHERE id=:id").param("n",next).param("lease",now().plus(LEASE)).param("id",id).update();return next;
            });
            Plan plan=null;String action="planning";
            try {
                List<Observation> observations=steps(id).stream().filter(s->s.observation()!=null).map(Step::observation).toList();
                plan=before.mode()==Mode.DEEPSEEK?model.nextMissionStep(before.goal(),allowed,observations):new Plan(List.of(new Call(allowed.get(0),json.valueToTree(Map.of()))),null,null);
                if(plan.calls()==null||plan.calls().size()!=1||plan.calls().get(0)==null||!allowed.contains(plan.calls().get(0).name())||plan.calls().get(0).arguments()==null||!plan.calls().get(0).arguments().isObject()||plan.calls().get(0).arguments().size()!=0)throw error(HttpStatus.BAD_GATEWAY,"MODEL_ACTION_REJECTED","模型动作不在当前允许范围内，未执行该动作");
                action=plan.calls().get(0).name();String chosen=action;Plan decision=plan;
                var after=write(()->{
                    var r=lease(user,session,id,token);
                    if(!tools.allowed(r.state()).contains(chosen))throw error(HttpStatus.CONFLICT,"MISSION_REPLAN_REQUIRED","执行条件已变化，请重新核对");
                    var result=tools.execute(user,chosen,r.state(),r.businessKey());
                    completeStep(id,number,chosen,result.observation().status(),r.state().message,result.observation(),decision);
                    return persist(r,r.state(),result.stage(),result.stage().equals("RUNNING")?token:null);
                });
                if(!after.stage().equals("RUNNING"))return view(user,after);
            }catch(ApiException failure){
                if(failure.status()==HttpStatus.UNAUTHORIZED||failure.status()==HttpStatus.FORBIDDEN)throw failure;
                var current=load(user,session,id);if(!Objects.equals(current.token(),token)||!current.stage().equals("RUNNING"))return view(user,current);
                String failedAction=action;Plan failedPlan=plan;
                var stopped=write(()->{
                    var r=lease(user,session,id,token);var s=r.state();
                    boolean replan=s.draftId==null&&(failure.code().equals("MISSION_REPLAN_REQUIRED")||Set.of("prepare_reservation","save_reservation","inspect_repair_location","prepare_repair","save_repair","inspect_leave_eligibility","prepare_leave","save_leave","inspect_certificate_identity","prepare_certificate","save_certificate").contains(failedAction)&&failure.status().is4xxClientError());
                    if(replan)tools.invalidate(s,failure.getMessage());else s.message=failure.code()+"："+failure.getMessage();
                    completeStep(id,number,failedAction,"FAILED",failure.code()+"："+failure.getMessage(),new Observation(failedAction,replan?"REPLAN_REQUIRED":"ERROR",0,s.missing),failedPlan);
                    return persist(r,s,replan?"WAITING_INPUT":"FAILED",null);
                });return view(user,stopped);
            }
        }
        var current=load(user,session,id);if(!Objects.equals(current.token(),token)||!current.stage().equals("RUNNING"))return view(user,current);
        return view(user,write(()->{var r=lease(user,session,id,token);r.state().message="本轮执行达到步数或时间预算，已保存进度。点击继续可恢复，任务总步数上限为24。";return persist(r,r.state(),"PAUSED",null);}));
    }
    private ApiException missing(){return error(HttpStatus.NOT_FOUND,"MISSION_NOT_FOUND","任务不存在或当前账号不可访问");}
    private ApiException conflict(String message){return error(HttpStatus.CONFLICT,"MISSION_CHANGED",message);}
    private ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
}
