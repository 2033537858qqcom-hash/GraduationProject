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
 private final AgentMissionTools missionTools;
 private final JsonMapper json=JsonMapper.builder().build();
 private static final Map<String,String> PERMISSIONS=Map.of("RESERVATION","reservation:apply","REPAIR","repair:apply","LEAVE","leave:apply","CERTIFICATE","certificate:apply");
 public AgentGoalTools(AgentMissionService missions,AgentGuides guides,IdentityService identities,Validator validator,AgentGoalBusiness business,JdbcClient jdbc,AgentMissionTools missionTools){this.missions=missions;this.guides=guides;this.identities=identities;this.validator=validator;this.business=business;this.jdbc=jdbc;this.missionTools=missionTools;}
 public record Outcome(String stage,String status,String message,Map<String,Object> observation){}
 public List<String> kinds(CampusPrincipal user){var permissions=identities.permissions(user.id());return PERMISSIONS.entrySet().stream().filter(e->permissions.contains(e.getValue())&&(!e.getKey().equals("RESERVATION")||permissions.contains("portal:read"))).map(Map.Entry::getKey).sorted().toList();}
 public AgentMissionModels.View mission(CampusPrincipal user,long session,Task task){return task.missionId==null?null:missions.detail(user,session,task.missionId);}
 public Task task(State state,String key){return state.tasks.stream().filter(t->Objects.equals(t.key,key)).findFirst().orElseThrow(()->invalid("GOAL_TASK_UNKNOWN","请选择当前计划中的任务"));}
 public List<TaskView> views(CampusPrincipal user,long session,State state){return state.tasks.stream().map(t->new TaskView(t.key,t.kind,t.label,t.target,t.dependsOn,t.cancelled,mission(user,session,t),t.submissionHash,t.submissionVerified,t.watching,business.preview(user,session,t),t.lastBusinessStatus,t.trackingComplete,t.lastCheckedAt)).toList();}
 public boolean authorized(State state){return state.tasks.stream().anyMatch(task->!task.cancelled&&task.submissionHash!=null);}
 public void authorize(CampusPrincipal user,long session,State state,String key,String hash,Boolean confirmed){business.authorize(user,session,task(state,key),hash,confirmed);}
 private Map<String,Guide> currentGuides(CampusPrincipal user){
  var result=new LinkedHashMap<String,Guide>();
  for(String kind:kinds(user))try{result.put(kind,guides.detail(kind.toLowerCase(Locale.ROOT)));}catch(ApiException failure){if(failure.status()!=HttpStatus.NOT_FOUND)throw failure;}
  return result;
 }
 public List<Map<String,Object>> schemas(CampusPrincipal user,long session,State state){
  if(!state.tasks.isEmpty()&&!state.directivePending&&state.tasks.stream().allMatch(task->complete(user,session,task)))
   return AgentGoalToolCatalog.tools(kinds(user),false).stream().filter(schema->((Map<?,?>)schema.get("function")).get("name").equals("finish")).toList();
  var current=currentGuides(user);var unread=current.entrySet().stream().filter(entry->!state.guidesRead.containsKey(entry.getKey())||state.guidesRead.get(entry.getKey()).version()!=entry.getValue().version()).map(Map.Entry::getKey).toList();
  var result=new ArrayList<Map<String,Object>>();
  for(var schema:AgentGoalToolCatalog.tools(kinds(user),authorized(state))){
   String name=((Map<?,?>)schema.get("function")).get("name").toString();
   if(name.equals("read_guide")){
    if(!unread.isEmpty())result.add(AgentGoalToolCatalog.tool("read_guide","读取尚未读取或版本变化的有效指南；已读指南节选会在上下文持续提供",Map.of("kind",AgentGoalToolCatalog.choice(unread)),"kind"));
   }else{
    var eligible=new ArrayList<String>();
    for(var task:state.tasks){
     if(task.cancelled)continue;var view=mission(user,session,task);boolean saved=view!=null&&view.business()!=null;
     boolean applicable=switch(name){
      case "set_task_fields"->!saved;
      case "prepare_task"->view==null||!saved&&view.canContinue();
      case "refresh_task"->view!=null;
      case "revise_reservation"->task.kind.equals("RESERVATION")&&view!=null&&!saved;
      case "select_venue"->view!=null&&view.stage().equals("WAITING_SELECTION");
      case "submit_task"->saved&&task.submissionHash!=null;
      case "verify_submission"->saved&&!Set.of("DRAFT","RETURNED").contains(view.business().status())&&!task.submissionVerified;
      case "watch_task"->saved&&task.target.equals("TRACK")&&task.submissionVerified&&!task.watching&&!task.trackingComplete;
      case "link_task_result"->task.missionId==null&&task.target.equals("TRACK")&&state.tasks.stream().anyMatch(source->!source.cancelled&&source.kind.equals(task.kind)&&source.submissionVerified&&task.dependsOn.contains(source.key));
      default->false;
     };if(applicable)eligible.add(task.key);
    }
    if(Set.of("set_plan","await_user","finish").contains(name)){result.add(schema);continue;}
    if(eligible.isEmpty())continue;
    @SuppressWarnings("unchecked") var function=new LinkedHashMap<String,Object>((Map<String,Object>)schema.get("function"));
    @SuppressWarnings("unchecked") var parameters=new LinkedHashMap<String,Object>((Map<String,Object>)function.get("parameters"));
    @SuppressWarnings("unchecked") var properties=new LinkedHashMap<String,Object>((Map<String,Object>)parameters.get("properties"));
    properties.put("taskKey",AgentGoalToolCatalog.choice(eligible));
    if(name.equals("set_task_fields")||name.equals("revise_reservation")){
     var names=new HashSet<String>();for(var task:state.tasks)if(eligible.contains(task.key))names.addAll(name.equals("revise_reservation")?Set.of("campus","startsAt","endsAt","startDate","startTime","endDate","endTime","participants","keyword"):fields(task.kind));
     @SuppressWarnings("unchecked") var shape=new LinkedHashMap<String,Object>((Map<String,Object>)properties.get("fields"));
     @SuppressWarnings("unchecked") var choices=new LinkedHashMap<String,Object>((Map<String,Object>)shape.get("properties"));
     choices.keySet().retainAll(names);shape.put("properties",choices);properties.put("fields",shape);
    }
    parameters.put("properties",properties);function.put("parameters",parameters);result.add(Map.of("type","function","function",function));
   }
  }
  return List.copyOf(result);
 }
 public boolean complete(CampusPrincipal user,long session,Task task){
  if(task.cancelled)return true;var view=mission(user,session,task);
  return view!=null&&view.stage().equals("DONE")&&view.business()!=null&&(task.target.equals("DRAFT")||task.submissionVerified&&(task.target.equals("SUBMIT")||task.trackingComplete));
 }
 public JsonNode context(CampusPrincipal user,long session,State state){
  var tasks=new ArrayList<Map<String,Object>>();
  for(var task:state.tasks){
   var item=new LinkedHashMap<String,Object>();item.put("key",task.key);item.put("kind",task.kind);item.put("label",task.label);item.put("target",task.target);item.put("dependsOn",task.dependsOn);item.put("cancelled",task.cancelled);
   var view=mission(user,session,task);item.put("stage",view==null?"NOT_PREPARED":view.stage());item.put("complete",complete(user,session,task));item.put("submissionAuthorized",task.submissionHash!=null);item.put("submissionVerified",task.submissionVerified);item.put("watching",task.watching);item.put("trackingComplete",task.trackingComplete);
   var present=new ArrayList<String>();Object local=currentFields(task,view);if(local!=null)for(var field:json.valueToTree(local).properties())if(fields(task.kind).contains(field.getKey())&&!field.getValue().isNull()&&(!field.getValue().isString()||!field.getValue().asText().isBlank()))present.add(field.getKey());
   item.put("providedFields",present);item.put("fieldsUpdatedForInstruction",task.fieldsInstructionCount);item.put("currentInstruction",state.instructions.size());
   if(view!=null){var facts=evidence(task,view);item.putAll(facts);item.put("businessStatus",view.business()==null?"NONE":view.business().status());item.put("conflictCount",view.conflicts().size());
    item.put("checks",view.steps().stream().map(AgentMissionModels.Step::observation).filter(Objects::nonNull).skip(Math.max(0,view.steps().size()-8)).toList());
    if(task.kind.equals("RESERVATION")){var f=view.fields();var query=new LinkedHashMap<String,Object>();query.put("campus",f.campus());query.put("startsAt",f.startsAt());query.put("endsAt",f.endsAt());query.put("participants",f.participants());query.put("keyword",f.keyword());item.put("search",query);
     item.put("options",view.options().stream().map(o->Map.of("key",o.key(),"name",o.name(),"location",o.location(),"capacity",o.capacity()==null?0:o.capacity())).toList());}
   }
   tasks.add(item);
  }
  var known=new ArrayList<Map<String,Object>>();var sources=currentGuides(user);
  for(String kind:kinds(user)){var source=sources.get(kind);var cached=state.guidesRead.get(kind);var item=new LinkedHashMap<String,Object>();item.put("kind",kind);item.put("status",source==null?"UNAVAILABLE":cached==null||cached.version()!=source.version()?"NEEDS_REFRESH":"CURRENT");
   if(source!=null)item.put("currentVersion",source.version());if(source!=null&&cached!=null&&cached.version()==source.version()){item.put("title",cached.title());item.put("excerpt",cached.body());}known.add(item);}
  return json.valueToTree(Map.of("time",AgentMissionTools.now().toString(),"instructions",state.instructions,"currentUserDirective",Objects.toString(state.currentUserDirective,""),"tasks",tasks,"guides",known,"observations",state.observations));
 }
 private Map<String,Object> evidence(Task task,AgentMissionModels.View view){
  var facts=new LinkedHashMap<String,Object>();facts.put("stage",view.stage());facts.put("optionCount",view.options().size());
  var missing=new ArrayList<>(view.missing());String queryStatus="NOT_APPLICABLE";
  if(task.kind.equals("RESERVATION")){
   var observation=view.steps().stream().filter(step->step.action().equals("find_venues")&&step.observation()!=null).reduce((first,last)->last).map(AgentMissionModels.Step::observation).orElse(null);
   if(observation!=null){queryStatus=observation.status().equals("NO_DATA")?"NO_CANDIDATES":observation.status().equals("OK")?"AVAILABLE":observation.status();
    if(queryStatus.equals("NO_CANDIDATES")&&missionTools.missing(view.fields()).isEmpty())missing.clear();
   }else queryStatus="NOT_QUERIED";
   facts.put("queryStatus",queryStatus);
   facts.put("nextChoices",!view.conflicts().isEmpty()?List.of("revise_reservation","await_user"):queryStatus.equals("NO_CANDIDATES")?List.of("revise_reservation","await_user"):view.stage().equals("WAITING_SELECTION")?List.of("select_venue","await_user"):List.of("await_user"));
  }
  facts.put("missing",missing);return facts;
 }
 private String evidenceHash(Task task,AgentMissionModels.View view){
  if(view==null)return "NOT_PREPARED";
  return missionTools.hash(Arrays.asList(evidence(task,view),view.options().stream().map(option->List.of(option.venueId(),option.venueVersion())).toList(),view.conflicts().stream().map(fact->fact.key()).toList(),currentFields(task,view),view.business()));
 }
 private Outcome outcome(String stage,String status,String message,Map<String,Object> observation){return new Outcome(stage,status,message,observation);}
 private Outcome observed(String tool,String task,String status,String message,Object value){return outcome("RUNNING",status,message,Map.of("tool",tool,"taskKey",Objects.toString(task,""),"status",status,"result",value));}
 private String text(JsonNode args,String field,int max,boolean required){var value=args.path(field);if(value.isMissingNode()&&!required)return null;if(!value.isString()||value.asText().isBlank()||value.asText().length()>max)throw invalid("GOAL_ARGUMENTS_INVALID","工具参数无效："+field);return value.asText().trim();}
 private void exact(JsonNode args,String... allowed){if(args==null||!args.isObject()||args.properties().stream().anyMatch(e->!Set.of(allowed).contains(e.getKey())))throw invalid("GOAL_ARGUMENTS_INVALID","工具包含未允许的参数");}
 private Object planStructure(List<Task> tasks){return tasks.stream().sorted(Comparator.comparing(task->task.key)).map(task->List.of(task.key,task.kind,task.target,task.dependsOn.stream().sorted().toList(),task.cancelled)).toList();}
 private void setPlan(CampusPrincipal user,long session,State state,JsonNode input){
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
  for(var previous:state.tasks)if(!keys.contains(previous.key)){
   if(!previous.cancelled&&!complete(user,session,previous))throw invalid("GOAL_PLAN_REMOVAL_REQUIRES_USER","尚未完成的事项不能直接删除，请由本人明确停止其后续执行");
   next.add(json.readValue(json.writeValueAsString(previous),Task.class));keys.add(previous.key);
  }
  for(var task:next)if(!keys.containsAll(task.dependsOn))throw invalid("GOAL_PLAN_INVALID","依赖指向不存在的任务");
  var done=new HashSet<String>();boolean progress=true;while(progress){progress=false;for(var task:next)if(!done.contains(task.key)&&done.containsAll(task.dependsOn)){done.add(task.key);progress=true;}}
  if(done.size()!=next.size())throw invalid("GOAL_PLAN_INVALID","任务依赖存在循环");
  if(!state.tasks.isEmpty()&&state.lastPlanEpoch==state.planEpoch&&planStructure(state.tasks).equals(planStructure(next)))throw invalid("GOAL_PLAN_UNCHANGED","计划没有新增事项、目标或依赖变化；请使用实际字段、查询、核验或等待本人操作，不要重发相同计划");
  state.tasks=next;state.lastPlanEpoch=state.planEpoch;state.directivePending=false;
 }
 private Object currentFields(Task task,AgentMissionModels.View view){return switch(task.kind){case "CERTIFICATE"->view==null?task.certificateFields:view.certificateFields();case "LEAVE"->view==null?task.leaveFields:view.leaveFields();case "REPAIR"->view==null?task.repairFields:view.repairFields();default->view==null?task.fields:view.fields();};}
 private Set<String> fields(String kind){return switch(kind){case "CERTIFICATE"->Set.of("purpose");case "LEAVE"->Set.of("reasonType","reason","startDate","startTime","endDate","endTime","startsAt","endsAt");case "REPAIR"->Set.of("category","title","description","locationDetail");default->Set.of("campus","startsAt","endsAt","startDate","startTime","endDate","endTime","participants","keyword","activityTheme","activityType");};}
 private String localTime(Object value){
  if(value==null)return null;String raw;if(value instanceof JsonNode node){if(node.isNull())return null;raw=node.asText();}else raw=value.toString();
  raw=raw.trim();if(raw.isBlank())return null;
  if(!raw.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:00)?"))throw invalid("GOAL_TIME_FORMAT","请使用明确的北京时间YYYY-MM-DDTHH:mm；不能把模糊时间当作已提供的条件");
  try{return java.time.LocalDateTime.parse(raw.replace(' ','T')).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));}
  catch(java.time.format.DateTimeParseException failure){throw invalid("GOAL_TIME_FORMAT","日期或时刻不合法，请核对YYYY-MM-DDTHH:mm格式");}
 }
 private boolean patch(CampusPrincipal user,long session,Task task,JsonNode patch,String operationKey,boolean searchOnly,int instructionCount){
  var allowed=searchOnly?Set.of("campus","startsAt","endsAt","startDate","startTime","endDate","endTime","participants","keyword"):fields(task.kind);
  if(!patch.isObject()||patch.isEmpty()||patch.properties().stream().anyMatch(e->!allowed.contains(e.getKey())||(!e.getKey().equals("participants")&&!e.getValue().isString())||(e.getKey().equals("participants")&&!e.getValue().isIntegralNumber())))throw invalid("GOAL_FIELDS_INVALID","条件包含未允许或类型不正确的字段，身份、地点和联系方式须在本地核对");
  var view=mission(user,session,task);if(view!=null&&view.business()!=null)throw invalid("GOAL_RESULT_EXISTS","原申请已保存，请到原页面修改；Agent保留历史");
  var values=new LinkedHashMap<String,Object>();var original=currentFields(task,view);if(original!=null)json.valueToTree(original).properties().forEach(e->values.put(e.getKey(),e.getValue()));patch.properties().forEach(e->values.put(e.getKey(),e.getValue()));
  if(task.kind.equals("LEAVE"))for(String prefix:List.of("start","end")){
   String alias=prefix+"sAt";if(values.containsKey(alias)){String stamp=localTime(values.remove(alias));if(stamp==null)throw invalid("GOAL_TIME_FORMAT","请提供明确请假时间");
    String day=stamp.substring(0,10),clock=stamp.substring(11);
    if(patch.has(prefix+"Date")&&!patch.path(prefix+"Date").asText().equals(day)||patch.has(prefix+"Time")&&!patch.path(prefix+"Time").asText().equals(clock))throw invalid("GOAL_TIME_FORMAT","完整请假时间与分开的日期时刻互相冲突，请核对");
    values.put(prefix+"Date",day);values.put(prefix+"Time",clock);
   }
  }
  if(task.kind.equals("RESERVATION")){
   for(String prefix:List.of("start","end")){
    if(patch.has(prefix+"Date")||patch.has(prefix+"Time")){
     String day=patch.path(prefix+"Date").asText(),clock=patch.path(prefix+"Time").asText();
     if(day.isBlank()||clock.isBlank())throw invalid("GOAL_TIME_FORMAT","分开的预约日期时间需要同时提供"+prefix+"Date和"+prefix+"Time；请核对明确日期，不能猜测");
     String value=localTime(day+"T"+clock);
     if(patch.has(prefix+"sAt")&&!Objects.equals(value,localTime(patch.path(prefix+"sAt"))))throw invalid("GOAL_TIME_FORMAT","两种预约时间格式互相冲突，请核对");
     values.put(prefix+"sAt",value);
    }
    values.remove(prefix+"Date");values.remove(prefix+"Time");
   }
   for(String name:List.of("startsAt","endsAt"))if(values.containsKey(name))values.put(name,localTime(values.get(name)));
  }
  Object typed;
  try{typed=switch(task.kind){case "CERTIFICATE"->missionTools.normalizeCertificate(json.convertValue(values,CertificateFields.class));case "LEAVE"->missionTools.normalizeLeave(json.convertValue(values,LeaveFields.class));case "REPAIR"->missionTools.normalizeRepair(json.convertValue(values,RepairFields.class));default->missionTools.normalize(json.convertValue(values,AgentMissionModels.Fields.class));};}
  catch(Exception invalid){throw invalid("GOAL_FIELDS_INVALID","条件类型不正确");}
  if(!validator.validate(typed).isEmpty())throw invalid("GOAL_FIELDS_INVALID","条件格式或长度超出业务限制");
  if(typed instanceof AgentMissionModels.Fields fields&&missionTools.missing(fields).contains("timeRange"))throw invalid("GOAL_TIME_RANGE","预约需要有效的未来时段，结束晚于开始，时长为30分钟至14小时；请按实际要求重新核对");
  boolean changed=!Objects.equals(original,typed);if(!changed&&task.fieldsInstructionCount==instructionCount)throw invalid("GOAL_FIELDS_UNCHANGED","当前任务已有相同条件，无需反复设置；请继续查询、核对预览或等待本人确认");
  if(view!=null&&changed)missions.action(user,session,view.id(),new AgentMissionModels.Action(operationKey,view.version(),"REVISE",typed instanceof AgentMissionModels.Fields f?f:null,null,null,null,typed instanceof RepairFields f?f:null,typed instanceof LeaveFields f?f:null,typed instanceof CertificateFields f?f:null));
  if(typed instanceof AgentMissionModels.Fields f)task.fields=f;else if(typed instanceof RepairFields f)task.repairFields=f;else if(typed instanceof LeaveFields f)task.leaveFields=f;else if(typed instanceof CertificateFields f)task.certificateFields=f;
  return changed;
 }
 public Outcome execute(CampusPrincipal user,long session,long goalId,int step,State state,Call call){
  String name=call.name();JsonNode args=call.arguments();if(args==null||!args.isObject())throw invalid("GOAL_ARGUMENTS_INVALID","工具参数须为对象");
  String operation="goal-"+goalId+"-step-"+step;
  switch(name){
   case "set_plan"->{exact(args,"tasks");setPlan(user,session,state,args.path("tasks"));return observed(name,null,"OK","已记录多业务任务计划和依赖，已结束事项的历史保留。",Map.of("taskCount",state.tasks.size()));}
   case "read_guide"->{exact(args,"kind");String kind=text(args,"kind",20,true);if(!kinds(user).contains(kind))throw invalid("GOAL_PLAN_INVALID","当前账号没有该业务权限");var guide=guides.detail(kind.toLowerCase(Locale.ROOT));state.guidesRead.put(kind,new Guide(guide.slug(),guide.title(),guide.body().substring(0,Math.min(2000,guide.body().length())),guide.version(),guide.updatedAt(),guide.targetPath()));return observed(name,null,"OK","已读取有效办理指南："+guide.title(),Map.of("kind",kind,"version",guide.version()));}
   case "set_task_fields","revise_reservation"->{exact(args,"taskKey","fields");var task=task(state,text(args,"taskKey",40,true));if(task.cancelled)throw invalid("GOAL_TASK_CANCELLED","该任务已取消");if(name.equals("revise_reservation")&&!task.kind.equals("RESERVATION"))throw invalid("GOAL_FIELDS_INVALID","只有预约任务可以调整场地查询方案");boolean changed=patch(user,session,task,args.path("fields"),operation,name.equals("revise_reservation"),state.instructions.size());task.fieldsInstructionCount=state.instructions.size();state.directivePending=false;return observed(name,task.key,changed?"OK":"UNCHANGED",changed?"已更新尚未写入的任务条件，后续须核对实际预览。":"已按本次补充核对现有条件，字段保持原值。",Map.of("updated",args.path("fields").properties().stream().map(Map.Entry::getKey).toList()));}
   case "prepare_task"->{
    exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));if(task.cancelled)throw invalid("GOAL_TASK_CANCELLED","该任务已取消");
    for(var dependency:task.dependsOn){var parent=task(state,dependency);if(parent.cancelled)throw invalid("GOAL_DEPENDENCY_CANCELLED","前置任务已由本人停止，请重新组织剩余依赖");if(!complete(user,session,parent))throw invalid("GOAL_DEPENDENCY_PENDING","前置任务尚未按目标完成");}
    var view=mission(user,session,task);boolean recovered=false;
    if(view==null){var prior=jdbc.sql("SELECT id FROM agent_mission WHERE session_id=:s AND user_id=:u AND request_key=:key").param("s",session).param("u",user.id()).param("key","goal-"+goalId+"-"+task.key).query(Long.class).optional();if(prior.isPresent()){view=missions.detail(user,session,prior.get());if(!task.kind.equals(view.kind()))throw invalid("GOAL_RECOVERY_MISMATCH","原子任务类型与当前计划不一致，请本人核对历史");task.missionId=view.id();recovered=true;}}
    ensureLocalDefaults(user,task);
    if(view==null){var body=new AgentMissionModels.Create("goal-"+goalId+"-"+task.key,task.label,Mode.LOCAL,false,task.fields,task.kind,task.repairFields,task.leaveFields,task.certificateFields);if(!validator.validate(body).isEmpty())throw invalid("GOAL_FIELDS_INVALID","任务条件未通过业务格式校验");view=missions.create(user,session,body);task.missionId=view.id();}
    else if(view.business()==null&&canReviseDefaults(view,task))view=missions.action(user,session,view.id(),new AgentMissionModels.Action(operation+"-defaults",view.version(),"REVISE",task.fields,null,null,null,task.repairFields,task.leaveFields,task.certificateFields));
    else if(view.canContinue())view=missions.action(user,session,view.id(),new AgentMissionModels.Action(operation,view.version(),"CONTINUE",null,null,null,null,null,null,null));
    else if(!recovered&&!canFulfill(view))throw invalid("GOAL_TASK_ALREADY_PREPARED","任务已有当前结果；缺少真实资料请询问，无候选请改查允许方案，等待确认时不要重复准备");
    view=fulfillOwned(user,session,operation,task,view);task.missionId=view.id();
    if(!state.directivePending&&state.tasks.stream().allMatch(item->complete(user,session,item)))return observedFinish();
    task.lastReadFingerprint=evidenceHash(task,view);task.unchangedReads=0;state.focusTaskKey=task.key;var facts=new LinkedHashMap<>(evidence(task,view));facts.put("recovered",recovered);facts.put("businessStatus",view.business()==null?"NONE":view.business().status());facts.put("watching",task.watching);
    String stage=task.watching?"MONITORING":"RUNNING";
    String message=task.watching?"已按你的办理授权提交并开始跟踪；状态变化时站内提醒。":view.business()!=null?"已按你的办理授权完成本人申请，结果以原申请为准。":recovered?"已恢复关联先前准备的本人子任务，保留原字段和业务记录。":"已核对原业务条件并准备任务预览。";
    return outcome(stage,"OK",message,Map.of("tool",name,"taskKey",task.key,"status","OK","result",facts));
   }
   case "refresh_task"->{
    exact(args,"taskKey");var task=task(state,text(args,"taskKey",40,true));var view=mission(user,session,task);if(view!=null&&view.business()==null&&Set.of("WAITING_INPUT","FAILED","INTERRUPTED").contains(view.stage()))view=missions.recheck(user,session,view.id(),operation);
    String hash=evidenceHash(task,view);boolean same=Objects.equals(hash,task.lastReadFingerprint);task.unchangedReads=same?task.unchangedReads+1:0;task.lastReadFingerprint=hash;
    var facts=view==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(evidence(task,view));facts.put("businessStatus",view==null||view.business()==null?"NONE":view.business().status());facts.put("complete",complete(user,session,task));facts.put("unchangedReads",task.unchangedReads);
    if(task.unchangedReads>=2){state.focusTaskKey=task.key;String stage=view!=null&&view.stage().equals("WAITING_CONFIRMATION")?"WAITING_CONFIRMATION":"WAITING_INPUT";return outcome(stage,"NO_CHANGE","连续核对后真实结果未变。请补充实际要求、调整可选方案或核对当前预览，避免重复查询。",Map.of("tool",name,"taskKey",task.key,"status","NO_CHANGE","result",facts));}
    return observed(name,task.key,same?"NO_CHANGE":"OK","已重新读取当前任务及原申请状态。",facts);
   }
   case "link_task_result"->{
    exact(args,"taskKey","sourceTaskKey");var task=task(state,text(args,"taskKey",40,true));var source=task(state,text(args,"sourceTaskKey",40,true));
    if(task.cancelled||source.cancelled||!task.target.equals("TRACK")||task.missionId!=null||!task.kind.equals(source.kind)||!task.dependsOn.contains(source.key)||!source.submissionVerified)throw invalid("GOAL_LINK_INVALID","跟踪步骤必须关联同业务、已核验提交的真实前置申请");
    var view=mission(user,session,source);if(view==null||view.business()==null)throw invalid("GOAL_LINK_INVALID","前置申请不存在或当前无权访问");
    task.missionId=source.missionId;task.submissionVerified=true;task.submittedVersion=source.submittedVersion;task.lastBusinessStatus=view.business().status();return observed(name,task.key,"LINKED","已关联前置事项的同一份本人申请；跟踪不新建业务记录。",Map.of("businessStatus",task.lastBusinessStatus));
   }
   case "select_venue"->{exact(args,"taskKey","optionKey");var task=task(state,text(args,"taskKey",40,true));if(!task.kind.equals("RESERVATION")||task.cancelled)throw invalid("GOAL_FIELDS_INVALID","当前任务不能选择预约场地");var view=mission(user,session,task);if(view==null)throw invalid("GOAL_TASK_NOT_PREPARED","请先查询当前预约候选");view=missions.action(user,session,view.id(),new AgentMissionModels.Action(operation,view.version(),"SELECT",null,text(args,"optionKey",50,true),null,null,null,null,null));view=fulfillOwned(user,session,operation,task,view);state.focusTaskKey=task.key;return observed(name,task.key,"OK",view.business()!=null?"已按你的办理授权保存所选场地方案。":"已从真实候选选择场地。",Map.of("stage",view.stage(),"businessStatus",view.business()==null?"NONE":view.business().status()));}
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
 private void ensureLocalDefaults(CampusPrincipal user,Task task){
  String contact=user.username()+"@campus.demo";String phone=String.format("139%08d",user.id());
  if("LEAVE".equals(task.kind)){var fields=task.leaveFields==null?new LeaveFields("","","","","","",""):task.leaveFields;if(fields.contact()==null||fields.contact().isBlank())task.leaveFields=new LeaveFields(fields.reasonType(),fields.reason(),contact,fields.startDate(),fields.startTime(),fields.endDate(),fields.endTime());}
  else if("REPAIR".equals(task.kind)){var fields=task.repairFields==null?new RepairFields("","","",null,"","",""):task.repairFields;if(fields.contact()==null||fields.contact().isBlank())task.repairFields=new RepairFields(fields.category(),fields.title(),fields.description(),fields.locationId(),fields.locationDetail(),contact,fields.contactWindow()==null||fields.contactWindow().isBlank()?"工作时间":fields.contactWindow());}
  else if("RESERVATION".equals(task.kind)&&task.fields!=null&&(task.fields.contactPhone()==null||task.fields.contactPhone().isBlank()))task.fields=new AgentMissionModels.Fields(task.fields.campus(),task.fields.startsAt(),task.fields.endsAt(),task.fields.participants(),task.fields.keyword(),task.fields.activityTheme(),task.fields.activityType(),phone);
 }
 private boolean canReviseDefaults(AgentMissionModels.View view,Task task){
  if("LEAVE".equals(task.kind)&&task.leaveFields!=null&&view.leaveFields()!=null)return !Objects.equals(task.leaveFields.contact(),view.leaveFields().contact());
  if("REPAIR".equals(task.kind)&&task.repairFields!=null&&view.repairFields()!=null)return !Objects.equals(task.repairFields.contact(),view.repairFields().contact());
  return "RESERVATION".equals(task.kind)&&task.fields!=null&&view.fields()!=null&&!Objects.equals(task.fields.contactPhone(),view.fields().contactPhone());
 }
 private boolean canFulfill(AgentMissionModels.View view){return view!=null&&(("WAITING_SELECTION".equals(view.stage())&&view.options()!=null&&!view.options().isEmpty())||("WAITING_CONFIRMATION".equals(view.stage())&&view.proposalHash()!=null&&!view.proposalHash().isBlank()));}
 private AgentMissionModels.View fulfillOwned(CampusPrincipal user,long session,String operation,Task task,AgentMissionModels.View view){
  if(view==null||task.cancelled)return view;
  if("WAITING_CONFIRMATION".equals(view.stage())&&view.proposalHash()!=null&&!view.proposalHash().isBlank()){
   view=missions.action(user,session,view.id(),new AgentMissionModels.Action(operation+"-confirm",view.version(),"CONFIRM",null,null,true,view.proposalHash(),null,null,null));
  }
  view=missions.detail(user,session,view.id());
  if(view.business()!=null&&"DRAFT".equals(view.business().status())&&Set.of("SUBMIT","TRACK").contains(task.target)){
   try{
    var preview=business.preview(user,session,task);
    if(preview!=null&&preview.canSubmit()){
     business.authorize(user,session,task,preview.proposalHash(),true);
     business.submit(user,session,task,operation+"-submit");
     business.verify(user,session,task);
     view=missions.detail(user,session,view.id());
     if("TRACK".equals(task.target)&&view.business()!=null){
      task.lastBusinessStatus=view.business().status();task.lastCheckedAt=AgentMissionTools.now();
      task.trackingComplete=business.terminal(task.lastBusinessStatus);task.watching=!task.trackingComplete;
     }
    }
   }catch(ApiException failure){if(failure.status()==HttpStatus.UNAUTHORIZED||failure.status()==HttpStatus.FORBIDDEN)throw failure;}
  }
  return view;
 }
 private ApiException invalid(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
