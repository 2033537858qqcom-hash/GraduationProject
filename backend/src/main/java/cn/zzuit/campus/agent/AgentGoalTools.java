package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentGoalModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bridges dynamic planning to real, scoped business tasks. It cannot manufacture human consent. */
@Service
public class AgentGoalTools {
 private final AgentMissionService missions;
 private final AgentGuides guides;
 private final IdentityService identities;
 private final Validator validator;
 private final AgentGoalBusiness business;
 private final JdbcClient jdbc;
 private final JsonMapper json=JsonMapper.builder().build();
 private static final Map<String,String> PERMISSIONS=Map.of("RESERVATION","reservation:apply","REPAIR","repair:apply","LEAVE","leave:apply","CERTIFICATE","certificate:apply");
 public AgentGoalTools(AgentMissionService missions,AgentGuides guides,IdentityService identities,Validator validator,AgentGoalBusiness business,JdbcClient jdbc){this.missions=missions;this.guides=guides;this.identities=identities;this.validator=validator;this.business=business;this.jdbc=jdbc;}
 public record Outcome(String stage,String status,String message,Map<String,Object> observation){}
 public List<String> kinds(CampusPrincipal user){var permissions=identities.permissions(user.id());return PERMISSIONS.entrySet().stream().filter(e->permissions.contains(e.getValue())&&(!e.getKey().equals("RESERVATION")||permissions.contains("portal:read"))).map(Map.Entry::getKey).sorted().toList();}
 public AgentMissionModels.View mission(CampusPrincipal user,long session,Task task){return task.missionId==null?null:missions.detail(user,session,task.missionId);}
 public Task task(State state,String key){return state.tasks.stream().filter(t->Objects.equals(t.key,key)).findFirst().orElseThrow(()->invalid("GOAL_TASK_UNKNOWN","请选择当前计划中的任务"));}
 public List<TaskView> views(CampusPrincipal user,long session,State state){return state.tasks.stream().map(t->new TaskView(t.key,t.kind,t.label,t.target,t.dependsOn,t.cancelled,mission(user,session,t),t.submissionHash,t.submissionVerified,t.watching,business.preview(user,session,t),t.lastBusinessStatus,t.trackingComplete,t.lastCheckedAt)).toList();}
 public boolean authorized(State state){return state.tasks.stream().anyMatch(task->!task.cancelled&&task.submissionHash!=null);}
 public void authorize(CampusPrincipal user,long session,State state,String key,String hash,Boolean confirmed){business.authorize(user,session,task(state,key),hash,confirmed);}
 public boolean complete(CampusPrincipal user,long session,Task task){
  if(task.cancelled)return true;var view=mission(user,session,task);
  return view!=null&&view.stage().equals("DONE")&&view.business()!=null&&(task.target.equals("DRAFT")||task.submissionVerified&&(task.target.equals("SUBMIT")||task.trackingComplete));
 }
 public JsonNode context(CampusPrincipal user,long session,State state){
  var tasks=new ArrayList<Map<String,Object>>();
  for(var task:state.tasks){
   var item=new LinkedHashMap<String,Object>();item.put("key",task.key);item.put("kind",task.kind);item.put("target",task.target);item.put("dependsOn",task.dependsOn);item.put("cancelled",task.cancelled);
   var view=mission(user,session,task);item.put("stage",view==null?"NOT_PREPARED":view.stage());item.put("complete",complete(user,session,task));item.put("submissionAuthorized",task.submissionHash!=null);item.put("submissionVerified",task.submissionVerified);item.put("watching",task.watching);item.put("trackingComplete",task.trackingComplete);
   if(view!=null){item.put("missing",view.missing());item.put("businessStatus",view.business()==null?"NONE":view.business().status());item.put("conflictCount",view.conflicts().size());
    if(task.kind.equals("RESERVATION")){var f=view.fields();var query=new LinkedHashMap<String,Object>();query.put("campus",f.campus());query.put("startsAt",f.startsAt());query.put("endsAt",f.endsAt());query.put("participants",f.participants());query.put("keyword",f.keyword());item.put("search",query);
     item.put("options",view.options().stream().map(o->Map.of("key",o.key(),"name",o.name(),"location",o.location(),"capacity",o.capacity()==null?0:o.capacity())).toList());}
   }
   tasks.add(item);
  }
  return json.valueToTree(Map.of("time",AgentMissionTools.now().toString(),"instructions",state.instructions,"tasks",tasks,"observations",state.observations));
 }
 private Outcome outcome(String stage,String status,String message,Map<String,Object> observation){return new Outcome(stage,status,message,observation);}
 private Outcome observed(String tool,String task,String status,String message,Object value){return outcome("RUNNING",status,message,Map.of("tool",tool,"taskKey",Objects.toString(task,""),"status",status,"result",value));}
 private String text(JsonNode args,String field,int max,boolean required){var value=args.path(field);if(value.isMissingNode()&&!required)return null;if(!value.isString()||value.asText().isBlank()||value.asText().length()>max)throw invalid("GOAL_ARGUMENTS_INVALID","工具参数无效："+field);return value.asText().trim();}
 private void exact(JsonNode args,String... allowed){if(args==null||!args.isObject()||args.properties().stream().anyMatch(e->!Set.of(allowed).contains(e.getKey())))throw invalid("GOAL_ARGUMENTS_INVALID","工具包含未允许的参数");}
 private void setPlan(CampusPrincipal user,State state,JsonNode input){
  if(!input.isArray()||input.isEmpty()||input.size()>6)throw invalid("GOAL_PLAN_INVALID","计划须包含1至6个任务");
  var next=new ArrayList<Task>();var keys=new HashSet<String>();var allowed=kinds(user);
  for(var row:input){
   exact(row,"key","kind","label","target","dependsOn");var key=text(row,"key",40,true);var kind=text(row,"kind",20,true);
   if(!key.matches("[a-z][a-z0-9_-]{0,39}")||!keys.add(key)||!allowed.contains(kind))throw invalid("GOAL_PLAN_INVALID","任务标识重复或超出当前业务权限");
   var target=text(row,"target",10,true);if(!Set.of("DRAFT","SUBMIT","TRACK").contains(target))throw invalid("GOAL_PLAN_INVALID","任务办理目标无效");
   var dependencies=row.path("dependsOn");if(!dependencies.isArray()||dependencies.size()>6)throw invalid("GOAL_PLAN_INVALID","任务依赖无效");
   var prior=state.tasks.stream().filter(t->t.key.equals(key)).findFirst().orElse(null);
   if(prior!=null&&!prior.kind.equals(kind))throw invalid("GOAL_PLAN_INVALID","已有任务不能更改业务类型，请保留历史并新增任务");
   // Work on copies: a rejected plan must not partially mutate the active plan.
   Task task=prior==null?new Task():json.readValue(json.writeValueAsString(prior),Task.class);
   task.key=key;task.kind=kind;task.label=text(row,"label",120,true);task.target=target;var parents=new ArrayList<String>();
   for(var dependency:dependencies){if(!dependency.isString()||dependency.asText().equals(key)||parents.contains(dependency.asText()))throw invalid("GOAL_PLAN_INVALID","任务依赖无效");parents.add(dependency.asText());}
   task.dependsOn=List.copyOf(parents);next.add(task);
  }
  if(state.tasks.stream().anyMatch(t->!keys.contains(t.key)))throw invalid("GOAL_PLAN_INVALID","已有任务须保留，取消请由本人明确操作");
  for(var task:next)if(!keys.containsAll(task.dependsOn))throw invalid("GOAL_PLAN_INVALID","依赖指向不存在的任务");
  var done=new HashSet<String>();boolean progress=true;while(progress){progress=false;for(var task:next)if(!done.contains(task.key)&&done.containsAll(task.dependsOn)){done.add(task.key);progress=true;}}
  if(done.size()!=next.size())throw invalid("GOAL_PLAN_INVALID","任务依赖存在循环");state.tasks=next;
 }
 private Object currentFields(Task task,AgentMissionModels.View view){return switch(task.kind){case "CERTIFICATE"->view==null?task.certificateFields:view.certificateFields();case "LEAVE"->view==null?task.leaveFields:view.leaveFields();case "REPAIR"->view==null?task.repairFields:view.repairFields();default->view==null?task.fields:view.fields();};}
 private Set<String> fields(String kind){return switch(kind){case "CERTIFICATE"->Set.of("purpose");case "LEAVE"->Set.of("reasonType","reason","startDate","startTime","endDate","endTime");case "REPAIR"->Set.of("category","title","description","locationDetail");default->Set.of("campus","startsAt","endsAt","participants","keyword","activityTheme","activityType");};}
 private void patch(CampusPrincipal user,long session,Task task,JsonNode patch,String operationKey,boolean searchOnly){
  var allowed=searchOnly?Set.of("campus","startsAt","endsAt","participants","keyword"):fields(task.kind);
  if(!patch.isObject()||patch.isEmpty()||patch.properties().stream().anyMatch(e->!allowed.contains(e.getKey())||(!e.getKey().equals("participants")&&!e.getValue().isString())||(e.getKey().equals("participants")&&!e.getValue().isIntegralNumber())))throw invalid("GOAL_FIELDS_INVALID","条件包含未允许或类型不正确的字段，身份、地点和联系方式须在本地核对");
  var view=mission(user,session,task);if(view!=null&&view.business()!=null)throw invalid("GOAL_RESULT_EXISTS","原申请已保存，请到原页面修改；Agent保留历史");
  var values=new LinkedHashMap<String,Object>();var original=currentFields(task,view);if(original!=null)json.valueToTree(original).properties().forEach(e->values.put(e.getKey(),e.getValue()));patch.properties().forEach(e->values.put(e.getKey(),e.getValue()));
  Object typed;
  try{typed=switch(task.kind){case "CERTIFICATE"->json.convertValue(values,CertificateFields.class);case "LEAVE"->json.convertValue(values,LeaveFields.class);case "REPAIR"->json.convertValue(values,RepairFields.class);default->json.convertValue(values,AgentMissionModels.Fields.class);};}
  catch(Exception invalid){throw invalid("GOAL_FIELDS_INVALID","条件类型不正确");}
  if(!validator.validate(typed).isEmpty())throw invalid("GOAL_FIELDS_INVALID","条件格式或长度超出业务限制");
  if(view!=null)missions.action(user,session,view.id(),new AgentMissionModels.Action(operationKey,view.version(),"REVISE",typed instanceof AgentMissionModels.Fields f?f:null,null,null,null,typed instanceof RepairFields f?f:null,typed instanceof LeaveFields f?f:null,typed instanceof CertificateFields f?f:null));
  if(typed instanceof AgentMissionModels.Fields f)task.fields=f;else if(typed instanceof RepairFields f)task.repairFields=f;else if(typed instanceof LeaveFields f)task.leaveFields=f;else if(typed instanceof CertificateFields f)task.certificateFields=f;
 }
 public Outcome execute(CampusPrincipal user,long session,long goalId,int step,State state,Call call){
  String name=call.name();JsonNode args=call.arguments();if(args==null||!args.isObject())throw invalid("GOAL_ARGUMENTS_INVALID","工具参数须为对象");
  String operation="goal-"+goalId+"-step-"+step;
  switch(name){
   case "set_plan"->{exact(args,"tasks");setPlan(user,state,args.path("tasks"));return observed(name,null,"OK","已记录多业务任务计划和依赖。",Map.of("taskCount",state.tasks.size()));}
   case "read_guide"->{exact(args,"kind");String kind=text(args,"kind",20,true);if(!kinds(user).contains(kind))throw invalid("GOAL_PLAN_INVALID","当前账号没有该业务权限");var guide=guides.detail(kind.equals("RESERVATION")?"reservation":kind.toLowerCase(Locale.ROOT));return observed(name,null,"OK","已读取有效办理指南："+guide.title(),Map.of("kind",kind,"version",guide.version(),"content",guide.body().substring(0,Math.min(2000,guide.body().length()))));}
   case "set_task_fields","revise_reservation"->{exact(args,"taskKey","fields");var task=task(state,text(args,"taskKey",40,true));if(task.cancelled)throw invalid("GOAL_TASK_CANCELLED","该任务已取消");if(name.equals("revise_reservation")&&!task.kind.equals("RESERVATION"))throw invalid("GOAL_FIELDS_INVALID","只有预约任务可以调整场地查询方案");patch(user,session,task,args.path("fields"),operation,name.equals("revise_reservation"));return observed(name,task.key,"OK","已更新尚未写入的任务条件，后续须核对实际预览。",Map.of("updated",args.path("fields").properties().stream().map(Map.Entry::getKey).toList()));}
   case "prepare_task"->{
    exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));if(task.cancelled)throw invalid("GOAL_TASK_CANCELLED","该任务已取消");
    for(var dependency:task.dependsOn){var parent=task(state,dependency);if(parent.cancelled)throw invalid("GOAL_DEPENDENCY_CANCELLED","前置任务已由本人停止，请重新组织剩余依赖");if(!complete(user,session,parent))throw invalid("GOAL_DEPENDENCY_PENDING","前置任务尚未按目标完成");}
    var view=mission(user,session,task);boolean recovered=false;
    if(view==null){var prior=jdbc.sql("SELECT id FROM agent_mission WHERE session_id=:s AND user_id=:u AND request_key=:key").param("s",session).param("u",user.id()).param("key","goal-"+goalId+"-"+task.key).query(Long.class).optional();if(prior.isPresent()){view=missions.detail(user,session,prior.get());if(!task.kind.equals(view.kind()))throw invalid("GOAL_RECOVERY_MISMATCH","原子任务类型与当前计划不一致，请本人核对历史");task.missionId=view.id();recovered=true;}}
    if(view==null){var body=new AgentMissionModels.Create("goal-"+goalId+"-"+task.key,task.label,Mode.LOCAL,false,task.fields,task.kind,task.repairFields,task.leaveFields,task.certificateFields);if(!validator.validate(body).isEmpty())throw invalid("GOAL_FIELDS_INVALID","任务条件未通过业务格式校验");view=missions.create(user,session,body);task.missionId=view.id();}
    else if(view.canContinue())view=missions.action(user,session,view.id(),new AgentMissionModels.Action(operation,view.version(),"CONTINUE",null,null,null,null,null,null,null));
    state.focusTaskKey=task.key;return observed(name,task.key,"OK",recovered?"已恢复关联先前准备的本人子任务，保留原字段和业务记录，请核对当前预览。":"已核对原业务条件并准备任务预览。",Map.of("stage",view.stage(),"missing",view.missing(),"optionCount",view.options().size(),"conflictCount",view.conflicts().size(),"recovered",recovered));
   }
   case "refresh_task"->{exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));var view=mission(user,session,task);if(view!=null&&view.business()==null&&Set.of("WAITING_INPUT","FAILED","INTERRUPTED").contains(view.stage()))view=missions.recheck(user,session,view.id(),operation);return observed(name,task.key,"OK","已重新读取当前任务及原申请状态。",Map.of("stage",view==null?"NOT_PREPARED":view.stage(),"businessStatus",view==null||view.business()==null?"NONE":view.business().status(),"complete",complete(user,session,task)));}
   case "select_venue"->{exact(args,"taskKey","optionKey");var task=task(state,text(args,"taskKey",40,true));if(!task.kind.equals("RESERVATION")||task.cancelled)throw invalid("GOAL_FIELDS_INVALID","当前任务不能选择预约场地");var view=mission(user,session,task);if(view==null)throw invalid("GOAL_TASK_NOT_PREPARED","请先查询当前预约候选");view=missions.action(user,session,view.id(),new AgentMissionModels.Action(operation,view.version(),"SELECT",null,text(args,"optionKey",50,true),null,null,null,null,null));state.focusTaskKey=task.key;return observed(name,task.key,"OK","已从真实候选选择场地，等待本人核对实际方案。",Map.of("stage",view.stage()));}
   case "await_user"->{exact(args,"taskKey","question");String key=text(args,"taskKey",40,false);var task=key==null?null:task(state,key);var view=task==null?null:mission(user,session,task);var submission=task==null?null:business.preview(user,session,task);state.focusTaskKey=key;String stage=view!=null&&view.stage().equals("WAITING_CONFIRMATION")||submission!=null&&submission.canSubmit()?"WAITING_CONFIRMATION":"WAITING_INPUT";return outcome(stage,"WAITING",text(args,"question",300,true),Map.of("tool",name,"taskKey",Objects.toString(key,""),"status",stage));}
   case "submit_task"->{exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));business.submit(user,session,task,operation);return observed(name,task.key,"SUBMITTED","已按本人授权提交原申请，正在等待回读核验。",Map.of("businessStatus","SUBMITTED"));}
   case "verify_submission"->{exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));String status=business.verify(user,session,task);return observed(name,task.key,"VERIFIED","已读取原申请核验真实提交结果和当前状态。",Map.of("businessStatus",status));}
   case "watch_task"->{
    exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));if(task.cancelled||!task.target.equals("TRACK")||!task.submissionVerified)throw invalid("GOAL_MONITOR_NOT_READY","跟踪任务必须在计划中明确要求TRACK并已核验实际提交");
    var view=mission(user,session,task);if(view==null||view.business()==null)throw invalid("GOAL_MONITOR_NOT_READY","尚未关联可跟踪的本人原申请");
    task.lastBusinessStatus=view.business().status();task.lastCheckedAt=AgentMissionTools.now();task.trackingComplete=business.terminal(task.lastBusinessStatus);task.watching=!task.trackingComplete;
    boolean allDone=state.tasks.stream().allMatch(t->complete(user,session,t));boolean waitingOnly=state.tasks.stream().allMatch(t->complete(user,session,t)||t.watching);
    return outcome(allDone?"DONE":waitingOnly?"MONITORING":"RUNNING","WATCHING",allDone?"办理跟踪已结束，结果以各原申请状态为准。":"已开始持久跟踪；状态不变时保持安静，变化或需要本人处理时发送站内提醒。",Map.of("tool",name,"taskKey",task.key,"status",task.trackingComplete?"DONE":"MONITORING","businessStatus",task.lastBusinessStatus));
   }
   case "finish"->{exact(args);if(state.tasks.isEmpty()||state.tasks.stream().anyMatch(t->!complete(user,session,t)))throw invalid("GOAL_NOT_COMPLETE","还有任务未按目标完成并核验，请继续执行或请求本人补充");return observedFinish();}
   default->throw invalid("GOAL_TOOL_REJECTED","模型选择的工具不在当前允许范围内");
  }
 }
 private Outcome observedFinish(){return outcome("DONE","DONE","目标中的全部任务已按办理目标完成并核验。",Map.of("tool","finish","status","DONE"));}
 public void cancelTask(CampusPrincipal user,long session,long goalId,Task task){if(task.missionId!=null){var view=mission(user,session,task);if(!Set.of("DONE","CANCELLED").contains(view.stage()))missions.action(user,session,view.id(),new AgentMissionModels.Action("goal-cancel-"+goalId+"-"+task.key,view.version(),"CANCEL",null,null,null,null,null,null,null));}}
 public void cancel(CampusPrincipal user,long session,long goalId,State state){for(var task:state.tasks)cancelTask(user,session,goalId,task);}
 private ApiException invalid(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
