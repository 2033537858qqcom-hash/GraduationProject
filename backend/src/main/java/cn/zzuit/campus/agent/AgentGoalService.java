package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentGoalModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.notice.NoticeWriter;
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

/** Leased goal-level observe/decide/act loop. Models never run inside a database transaction. */
@Service
public class AgentGoalService {
 private static final int STEPS_PER_RUN=8,TOTAL_STEPS=80;
 private static final Duration LEASE=Duration.ofSeconds(120),BUDGET=Duration.ofSeconds(60);
 private final JdbcClient jdbc;
 private final AgentSessions sessions;
 private final AgentGoalTools tools;
 private final AgentMissionTools hashes;
 private final DeepSeekClient model;
 private final TransactionTemplate tx;
 private final IdentityService identities;
 private final NoticeWriter notices;
 private final AgentGoalBusiness business;
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AgentGoalService.class);
 private final JsonMapper json=JsonMapper.builder().build();
 private record Row(long id,long session,long user,String goal,boolean consent,long security,String stage,State state,int count,int version,String token,LocalDateTime lease,String lastKey,String lastHash,LocalDateTime updated){}
 private record Claim(Row row,boolean run){}
 public AgentGoalService(JdbcClient jdbc,AgentSessions sessions,AgentGoalTools tools,AgentMissionTools hashes,DeepSeekClient model,TransactionTemplate tx,IdentityService identities,NoticeWriter notices,AgentGoalBusiness business){this.jdbc=jdbc;this.sessions=sessions;this.tools=tools;this.hashes=hashes;this.model=model;this.tx=tx;this.identities=identities;this.notices=notices;this.business=business;}
 private LocalDateTime now(){return AgentMissionTools.now();}
 private <T>T write(Supplier<T> operation){return tx.execute(ignored->{jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();return operation.get();});}
 private Row row(ResultSet r,int ignored)throws SQLException{return new Row(r.getLong("id"),r.getLong("session_id"),r.getLong("user_id"),r.getString("goal"),r.getBoolean("external_consent"),r.getLong("security_version"),r.getString("stage"),json.readValue(r.getString("state_json"),State.class),r.getInt("step_count"),r.getInt("version"),r.getString("runner_token"),r.getObject("lease_until",LocalDateTime.class),r.getString("last_request_key"),r.getString("last_request_hash"),r.getObject("updated_at",LocalDateTime.class));}
 private Row raw(long id){return jdbc.sql("SELECT * FROM agent_goal WHERE id=:id").param("id",id).query(this::row).single();}
 private Row load(CampusPrincipal user,long session,long id){sessions.session(user,session);return jdbc.sql("SELECT * FROM agent_goal WHERE id=:id AND session_id=:s AND user_id=:u").param("id",id).param("s",session).param("u",user.id()).query(this::row).optional().orElseThrow(()->error(HttpStatus.NOT_FOUND,"GOAL_NOT_FOUND","目标不存在或当前账号无权访问"));}
 private boolean running(Row row){return row.stage.equals("RUNNING")&&row.lease!=null&&row.lease.isAfter(now());}
 private Row lease(CampusPrincipal user,long session,long id,String token){var row=load(user,session,id);if(!row.stage.equals("RUNNING")||!Objects.equals(row.token,token))throw changed("目标执行权已变化，请刷新状态");return row;}
 private Row persist(Row row,State state,String stage,String token){
  if(jdbc.sql("UPDATE agent_goal SET state_json=:state,stage=:stage,runner_token=:token,lease_until=:lease,monitor_after=CASE WHEN :watching THEN COALESCE(monitor_after,:monitor) ELSE NULL END,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
      .param("state",json.writeValueAsString(state)).param("stage",stage).param("token",token).param("lease",token==null?null:now().plus(LEASE)).param("watching",state.tasks.stream().anyMatch(task->task.watching)).param("monitor",now().plusMinutes(1)).param("id",row.id).param("v",row.version).update()!=1)throw changed("目标状态已变化，请刷新");return raw(row.id);
 }
 public List<Summary> list(CampusPrincipal user,long session){sessions.session(user,session);return jdbc.sql("SELECT * FROM agent_goal WHERE session_id=:s AND user_id=:u ORDER BY id DESC LIMIT 10").param("s",session).param("u",user.id()).query((r,n)->new Summary(r.getLong("id"),r.getString("request_key"),r.getString("goal"),r.getString("stage").equals("RUNNING")&&(r.getObject("lease_until",LocalDateTime.class)==null||r.getObject("lease_until",LocalDateTime.class).isBefore(now()))?"INTERRUPTED":r.getString("stage"),r.getInt("version"),r.getObject("updated_at",LocalDateTime.class))).list();}
 private List<Event> events(long id){return jdbc.sql("SELECT * FROM agent_goal_event WHERE goal_id=:id ORDER BY step_number").param("id",id).query((r,n)->new Event(r.getInt("step_number"),r.getString("tool"),r.getString("task_key"),r.getString("status"),r.getString("message"),r.getObject("prompt_tokens",Integer.class),r.getObject("completion_tokens",Integer.class),r.getObject("created_at",LocalDateTime.class))).list();}
 private View view(CampusPrincipal user,Row row){String stage=row.stage.equals("RUNNING")&&!running(row)?"INTERRUPTED":row.stage;return new View(row.id,row.session,row.goal,stage,row.version,List.copyOf(row.state.instructions),tools.views(user,row.session,row.state),row.state.message,row.state.focusTaskKey,events(row.id),row.count<TOTAL_STEPS&&!Set.of("RUNNING","DONE","CANCELLED").contains(stage),row.updated);}
 public View detail(CampusPrincipal user,long session,long id){return view(user,load(user,session,id));}
 private void consent(boolean agreed){if(!agreed)throw error(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次目标、补充文字、公开办理指南与候选、脱敏执行结果发送至DeepSeek");if(!model.ready())throw error(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","自主目标规划需要连接DeepSeek；可使用原有本地草稿流程");}
 public View create(CampusPrincipal user,long session,Create input){
  sessions.session(user,session);String hash=hashes.hash(input);
  Claim claim=write(()->{
   sessions.session(user,session);var prior=jdbc.sql("SELECT * FROM agent_goal WHERE session_id=:s AND request_key=:k").param("s",session).param("k",input.requestKey()).query(this::row).optional();
   if(prior.isPresent()){var row=prior.get();String original=jdbc.sql("SELECT create_hash FROM agent_goal WHERE id=:id").param("id",row.id).query(String.class).single();if(!original.equals(hash))throw changed("重复目标请求与原内容不同");return new Claim(row,false);}
   consent(Boolean.TRUE.equals(input.externalConsent()));if(tools.kinds(user).isEmpty())throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","当前岗位没有可办理的Agent业务权限");
   if(jdbc.sql("SELECT COUNT(*) FROM agent_goal WHERE session_id=:s").param("s",session).query(Long.class).single()>=5)throw changed("本次对话已有5个目标，请新建对话继续");
   String token=UUID.randomUUID().toString();var key=new GeneratedKeyHolder();
   jdbc.sql("INSERT INTO agent_goal(session_id,user_id,request_key,create_hash,goal,external_consent,security_version,stage,state_json,runner_token,lease_until) VALUES(:s,:u,:key,:hash,:goal,TRUE,:security,'RUNNING',:state,:token,:lease)")
       .param("s",session).param("u",user.id()).param("key",input.requestKey()).param("hash",hash).param("goal",input.goal().trim()).param("security",user.securityVersion()).param("state",json.writeValueAsString(new State())).param("token",token).param("lease",now().plus(LEASE)).update(key,"id");
   String title="Agent目标："+input.goal().trim();jdbc.sql("UPDATE agent_session SET updated_at=CURRENT_TIMESTAMP,title=CASE WHEN title='新对话' THEN :title ELSE title END WHERE id=:id").param("title",title.substring(0,Math.min(title.length(),60))).param("id",session).update();
   return new Claim(load(user,session,key.getKey().longValue()),true);
  });return claim.run?run(user,session,claim.row.id,claim.row.token):view(user,claim.row);
 }
 public View action(CampusPrincipal user,long session,long id,Action input){
  Claim claim=write(()->{
   var row=load(user,session,id);String hash=hashes.hash(input);
   if(input.requestKey().equals(row.lastKey)){if(!hash.equals(row.lastHash))throw changed("重复操作请求与原内容不同");return new Claim(row,false);}
   if(input.action().equals("CANCEL")){
    if(Set.of("DONE","CANCELLED").contains(row.stage))return new Claim(row,false);row.state.message="已取消目标后续执行；已保存的原业务申请保留。";for(var task:row.state.tasks){task.watching=false;task.submissionHash=null;}
    jdbc.sql("UPDATE agent_goal_event SET status='CANCELLED',message='本人已取消后续执行；本步返回的模型建议不会继续执行' WHERE goal_id=:id AND status='RUNNING'").param("id",id).update();
    remember(row,input,hash);return new Claim(persist(row,row.state,"CANCELLED",null),false);
   }
   if(input.version()!=row.version)throw changed("目标版本已变化，请刷新后核对");if(running(row))throw changed("目标正在执行，请等待或取消");
   if(row.stage.equals("CANCELLED")||row.stage.equals("DONE")&&!input.action().equals("REVISE"))throw changed("目标已结束，请查看原记录或新建目标");
   switch(input.action()){
    case "CONTINUE"->{}
    case "REVISE"->{consent(Boolean.TRUE.equals(input.externalConsent()));if(input.message()==null||input.message().isBlank())throw error(HttpStatus.BAD_REQUEST,"GOAL_MESSAGE_REQUIRED","请明确说明目标如何调整");if(row.state.instructions.size()>=8)throw changed("本目标已有8次补充，请新建目标以保持要求清晰");row.state.instructions.add(input.message().trim());row.state.currentUserDirective=input.message().trim();row.state.directivePending=true;row.state.planEpoch++;for(var task:row.state.tasks){task.submissionHash=null;task.unchangedReads=0;}row.state.failures=0;observe(row.state,Map.of("tool","user_revision","status","USER_REQUIREMENTS_UPDATED","instructionIndex",row.state.instructions.size()));}
    case "AUTHORIZE_SUBMIT"->{tools.authorize(user,session,row.state,input.taskKey(),input.proposalHash(),input.confirmed());observe(row.state,Map.of("tool","human_authorization","status","SUBMISSION_AUTHORIZED","taskKey",input.taskKey(),"target",tools.task(row.state,input.taskKey()).target));}
    case "CANCEL_TASK"->{if(!Boolean.TRUE.equals(input.confirmed()))throw error(HttpStatus.BAD_REQUEST,"GOAL_CANCEL_CONFIRM_REQUIRED","请明确确认停止这个事项的后续执行，原申请仍保留");var task=tools.task(row.state,input.taskKey());task.cancelled=true;task.watching=false;task.submissionHash=null;row.state.directivePending=true;row.state.currentUserDirective="本人已停止"+Map.of("RESERVATION","场地预约","CERTIFICATE","在校证明","LEAVE","请假","REPAIR","报修").get(task.kind)+"事项（"+task.key+"）的后续办理；保留其历史，不得按原目标重新创建替代任务，请继续其余事项。";row.state.planEpoch++;row.state.focusTaskKey=row.state.tasks.stream().filter(item->!item.cancelled).map(item->item.key).findFirst().orElse(null);observe(row.state,Map.of("tool","human_cancellation","taskKey",task.key,"status","TASK_CANCELLED"));}
    default->throw error(HttpStatus.BAD_REQUEST,"GOAL_ACTION_INVALID","当前目标不支持该操作");
   }
   if(input.action().equals("CANCEL_TASK")&&(row.state.tasks.stream().allMatch(task->task.cancelled)||!model.ready())){remember(row,input,hash);boolean all=row.state.tasks.stream().allMatch(task->task.cancelled);row.state.message=all?"所有计划事项已停止后续执行，原业务记录保留。":"这个事项已停止后续执行，其他计划保留；模型连接后可继续。";return new Claim(persist(row,row.state,all?"CANCELLED":"PAUSED",null),false);}
   if(row.count>=TOTAL_STEPS){row.state.message="目标已达到80步执行上限，请核对历史后新建目标。";return new Claim(persist(row,row.state,"FAILED",null),false);}
   consent(row.consent);remember(row,input,hash);String token=UUID.randomUUID().toString();
   jdbc.sql("UPDATE agent_goal SET security_version=:security WHERE id=:id").param("security",user.securityVersion()).param("id",id).update();
   jdbc.sql("UPDATE agent_goal_event SET status='INTERRUPTED',message='上轮执行已中断，重新读取结果后继续决策' WHERE goal_id=:id AND status='RUNNING'").param("id",id).update();
   return new Claim(persist(row,row.state,"RUNNING",token),true);
  });
  if(input.action().equals("CANCEL")){tools.cancel(user,session,id,claim.row.state);return view(user,claim.row);}
  if(input.action().equals("CANCEL_TASK"))tools.cancelTask(user,session,id,tools.task(claim.row.state,input.taskKey()));
  return claim.run?run(user,session,id,claim.row.token):view(user,claim.row);
 }
 private void remember(Row row,Action input,String hash){jdbc.sql("UPDATE agent_goal SET last_request_key=:key,last_request_hash=:hash WHERE id=:id").param("key",input.requestKey()).param("hash",hash).param("id",row.id).update();}
 private String taskKey(Call call){String key=call==null||call.arguments()==null?null:call.arguments().path("taskKey").asString(null);return key!=null&&key.matches("[a-z][a-z0-9_-]{0,39}")?key:null;}
 private String toolName(Call call){return call==null?"planning":call.name()!=null&&call.name().matches("[a-z_]{1,40}")?call.name():"invalid_tool";}
 private void finishEvent(long id,int number,Call call,String status,String message,Map<String,Object> observation,Plan plan){jdbc.sql("UPDATE agent_goal_event SET tool=:tool,task_key=:task,status=:status,message=:message,arguments_json=:args,observation_json=:observation,prompt_tokens=:prompt,completion_tokens=:completion WHERE goal_id=:id AND step_number=:step")
     .param("tool",toolName(call)).param("task",taskKey(call)).param("status",status).param("message",message.substring(0,Math.min(500,message.length()))).param("args",call==null?null:json.writeValueAsString(call.arguments())).param("observation",json.writeValueAsString(observation)).param("prompt",plan==null?null:plan.promptTokens()).param("completion",plan==null?null:plan.completionTokens()).param("id",id).param("step",number).update();}
 private void observe(State state,Map<String,Object> observation){state.observations.add(observation);while(state.observations.size()>12)state.observations.removeFirst();}
 private Map<String,Object> actualObservation(AgentGoalTools.Outcome result,Plan plan){var observation=new LinkedHashMap<String,Object>(result.observation());if(plan.calls().size()>1)observation.put("deferredTools",plan.calls().stream().skip(1).map(this::toolName).toList());return observation;}
 private View run(CampusPrincipal user,long session,long id,String token){
  long deadline=System.nanoTime()+BUDGET.toNanos();
  for(int iteration=0;iteration<STEPS_PER_RUN&&System.nanoTime()<deadline;iteration++){
   var before=load(user,session,id);if(!before.stage.equals("RUNNING")||!Objects.equals(before.token,token))return view(user,before);
   if(before.count>=TOTAL_STEPS)return stop(user,session,id,token,"FAILED","目标达到80步上限，请核对执行记录后新建目标。");
   int number=write(()->{var row=lease(user,session,id,token);int next=row.count+1;
    jdbc.sql("INSERT INTO agent_goal_event(goal_id,step_number,tool,status,message,observation_json) VALUES(:id,:step,'planning','RUNNING','正在读取当前结果并自主选择下一工具','{}')").param("id",id).param("step",next).update();
    jdbc.sql("UPDATE agent_goal SET step_count=:step,lease_until=:lease WHERE id=:id").param("step",next).param("lease",now().plus(LEASE)).param("id",id).update();return next;});
   Plan plan=null;Call call=null;
   try{
    var schemas=tools.schemas(user,session,before.state);
    plan=model.nextGoalStep(before.goal,schemas,tools.context(user,session,before.state));
    if(plan==null||plan.calls()==null||plan.calls().isEmpty()||plan.calls().size()>4||plan.calls().getFirst()==null)throw error(HttpStatus.BAD_GATEWAY,"GOAL_MODEL_ACTION_INVALID","模型必须提供有效的工具建议；本轮只执行首项，其他建议需等待实际结果后重新决策");
    call=plan.calls().getFirst();String chosen=call.name();if(chosen==null||schemas.stream().noneMatch(s->((Map<?,?>)s.get("function")).get("name").equals(chosen))||call.arguments()==null||!call.arguments().isObject())throw error(HttpStatus.BAD_GATEWAY,"GOAL_TOOL_REJECTED","模型动作不在当前工具范围内");
    lease(user,session,id,token);
    // Child task preparation is idempotent and local; never hold the goal lock across its executor.
    Plan decided=plan;Call executed=call;Row after;
    if(chosen.equals("submit_task")){
     // Recheck lease, exact authorization and original business under the same scope/write lock.
     after=write(()->{var row=lease(user,session,id,token);var result=tools.execute(user,session,id,number,row.state,executed);var observation=actualObservation(result,decided);row.state.message=result.message();row.state.failures=0;observe(row.state,observation);finishEvent(id,number,executed,result.status(),result.message(),observation,decided);return persist(row,row.state,result.stage(),token);});
    }else{
     var result=tools.execute(user,session,id,number,before.state,call);
     after=write(()->{var row=lease(user,session,id,token);var state=before.state;var observation=actualObservation(result,decided);state.message=result.message();state.failures=0;observe(state,observation);finishEvent(id,number,executed,result.status(),result.message(),observation,decided);return persist(row,state,result.stage(),result.stage().equals("RUNNING")?token:null);});
    }
    if(!after.stage.equals("RUNNING"))return view(user,after);
   }catch(ApiException failure){
    var current=raw(id);if(!current.stage.equals("RUNNING")||!Objects.equals(current.token,token))return view(user,current);
    Plan failedPlan=plan;Call failedCall=call;
    var after=write(()->{
     var row=raw(id);if(!row.stage.equals("RUNNING")||!Objects.equals(row.token,token))return row;
     var state=row.state;state.failures++;state.message=failure.getMessage();
     if(failure.code().equals("GOAL_SUBMISSION_CHANGED")&&taskKey(failedCall)!=null)state.tasks.stream().filter(task->task.key.equals(taskKey(failedCall))).forEach(task->task.submissionHash=null);
     var observation=Map.<String,Object>of("tool",toolName(failedCall),"taskKey",Objects.toString(taskKey(failedCall),""),"status","ERROR","code",failure.code());observe(state,observation);
     finishEvent(id,number,failedCall,"FAILED",failure.code()+"："+failure.getMessage(),observation,failedPlan);
     boolean authentication=failure.status()==HttpStatus.UNAUTHORIZED;boolean provider=failure.code().startsWith("MODEL_");
     String stage=authentication||state.failures>=3?"FAILED":provider?"PAUSED":"RUNNING";
     return persist(row,state,stage,stage.equals("RUNNING")?token:null);
    });
    if(failure.status()==HttpStatus.UNAUTHORIZED)throw failure;
    if(!after.stage.equals("RUNNING"))return view(user,after);
   }
  }
  var current=load(user,session,id);if(!current.stage.equals("RUNNING")||!Objects.equals(current.token,token))return view(user,current);
  if(current.count>=TOTAL_STEPS)return stop(user,session,id,token,"FAILED","目标达到80步上限，请核对执行记录后新建目标。");
  return stop(user,session,id,token,"PAUSED","本轮预算已用完，计划与结果已保存；继续执行会重新读取当前状态。每个目标最多80步。");
 }
 private View stop(CampusPrincipal user,long session,long id,String token,String stage,String message){return view(user,write(()->{var row=lease(user,session,id,token);row.state.message=message;return persist(row,row.state,stage,null);}));}
 /** Background checks use the stored owner and security version; no model calls or arbitrary actors. */
 public int pollMonitored(){
  var ids=jdbc.sql("SELECT id FROM agent_goal WHERE monitor_after<=:now AND runner_token IS NULL AND stage IN ('MONITORING','WAITING_INPUT','WAITING_CONFIRMATION','PAUSED') ORDER BY monitor_after,id LIMIT 50").param("now",now()).query(Long.class).list();int changed=0;
  for(long id:ids){try{changed+=write(()->pollOne(id));}catch(RuntimeException failure){LOG.warn("Agent monitor will retry goal {} after {}",id,failure.getClass().getSimpleName());}}
  return changed;
 }
 private int pollOne(long id){
  var found=jdbc.sql("SELECT * FROM agent_goal WHERE id=:id").param("id",id).query(this::row).optional();if(found.isEmpty())return 0;var row=found.get();
  if(row.token!=null||!Set.of("MONITORING","WAITING_INPUT","WAITING_CONFIRMATION","PAUSED").contains(row.stage))return 0;
  var owner=identities.current(row.user).orElse(null);
  if(owner==null||owner.securityVersion()!=row.security){for(var task:row.state.tasks)task.watching=false;row.state.message="账号或岗位授权已变化，已停止后台跟踪；请重新登录后明确继续。";persist(row,row.state,"PAUSED",null);return 0;}
  int changes=0;boolean unavailable=false;
  for(var task:row.state.tasks){
   if(!task.watching||task.cancelled)continue;
   try{
    var view=tools.mission(owner,row.session,task);if(view==null||view.business()==null)throw changed("原申请当前不可读取");var current=view.business();
    if(!Objects.equals(task.lastBusinessStatus,current.status())){
     notices.write(owner.id(),"Agent 跟踪状态更新",task.kind,current.id(),"MINE","状态由“"+business.statusLabel(task.lastBusinessStatus)+"”变为“"+business.statusLabel(current.status())+"”，请进入原申请查看办理意见和下一步。");
     task.lastBusinessStatus=current.status();task.lastCheckedAt=now();row.state.focusTaskKey=task.key;changes++;
    }
    if(business.terminal(current.status())){task.trackingComplete=true;task.watching=false;}
   }catch(ApiException failure){task.watching=false;task.submissionHash=null;unavailable=true;row.state.message="部分事项的当前访问授权或原申请已变化，已停止其跟踪，请本人重新核对。";}
  }
  boolean complete=!unavailable&&!row.state.tasks.isEmpty();
  if(complete)for(var task:row.state.tasks){try{if(!tools.complete(owner,row.session,task)){complete=false;break;}}catch(ApiException failure){complete=false;unavailable=true;row.state.message="部分事项当前已无访问权限，请本人重新核对；跟踪不会绕过权限。";break;}}
  if(changes>0||unavailable||complete){
   String stage=complete?"DONE":unavailable?"PAUSED":row.stage;
   if(complete)row.state.message="办理跟踪已结束，结果以各原申请的真实状态为准。";else if(!unavailable)row.state.message="原申请状态已更新，请查看对应任务、办理意见与下一步。";
   persist(row,row.state,stage,null);
  }
  jdbc.sql("UPDATE agent_goal SET monitor_after=:next WHERE id=:id AND monitor_after IS NOT NULL").param("next",now().plusMinutes(1)).param("id",id).update();return changes;
 }
 private ApiException changed(String message){return error(HttpStatus.CONFLICT,"GOAL_CHANGED",message);}
 private ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
}
