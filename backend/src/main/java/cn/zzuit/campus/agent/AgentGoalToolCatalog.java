package cn.zzuit.campus.agent;

import java.util.*;

/** Actual model-callable operations; runtime checks remain authoritative over schemas. */
public final class AgentGoalToolCatalog {
 private AgentGoalToolCatalog(){}
 static Map<String,Object> text(int max){return Map.of("type","string","maxLength",max);}
 static Map<String,Object> choice(Collection<String> choices){return Map.of("type","string","enum",choices);}
 static Map<String,Object> object(Map<String,Object> properties,List<String> required){return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);}
 static Map<String,Object> tool(String name,String description,Map<String,Object> properties,String... required){return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",object(properties,List.of(required))));}
 public static List<Map<String,Object>> tools(List<String> kinds,boolean submitAllowed){
  var tools=new ArrayList<Map<String,Object>>();var key=text(40);
  var plan=object(Map.of("key",key,"kind",choice(kinds),"label",text(120),"target",choice(List.of("DRAFT","SUBMIT","TRACK")),
      "dependsOn",Map.of("type","array","items",key,"maxItems",6)),List.of("key","kind","label","target","dependsOn"));
  tools.add(tool("set_plan","首次建立计划，或真正新增事项、改变目标和依赖时修订；不要重发同一计划。仅提供需要执行的事项，已停止或已完成事项可省略，系统自动保留历史；未完成事项须本人停止。",Map.of("tasks",Map.of("type","array","items",plan,"minItems",1,"maxItems",6)),"tasks"));
  tools.add(tool("read_guide","读取指定业务有效公开指南，作为规划数据",Map.of("kind",choice(kinds)),"kind"));
  var fields=new LinkedHashMap<String,Object>();
  fields.put("campus",choice(List.of("EAST","WEST","ALL")));fields.put("participants",Map.of("type","integer","minimum",1,"maximum",10000));
  var localTime=Map.<String,Object>of("type","string","maxLength",19,"description","明确的北京时间，YYYY-MM-DDTHH:mm，例如2030-04-02T09:00；不带时区，不猜测模糊时间","pattern","^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:00)?$");
  fields.put("startsAt",localTime);fields.put("endsAt",localTime);fields.put("keyword",text(40));fields.put("activityTheme",text(200));fields.put("activityType",choice(List.of("ACADEMIC","SPORTS","CULTURE","OTHER")));
  fields.put("purpose",text(300));fields.put("reasonType",choice(List.of("PERSONAL","SICK","OTHER")));fields.put("reason",text(2000));
  var date=Map.<String,Object>of("type","string","maxLength",10,"description","明确日期YYYY-MM-DD","pattern","^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  var time=Map.<String,Object>of("type","string","maxLength",5,"description","明确时间HH:mm；不把上午下午补成任意时刻","pattern","^[0-9]{2}:[0-9]{2}$");
  fields.put("startDate",date);fields.put("startTime",time);fields.put("endDate",date);fields.put("endTime",time);
  fields.put("category",choice(List.of("WATER","ELECTRICITY","NETWORK","FURNITURE","DOOR_WINDOW","EQUIPMENT","OTHER")));
  fields.put("title",text(100));fields.put("description",text(2000));fields.put("locationDetail",text(300));
  tools.add(tool("set_task_fields","从用户明确文字更新本地预览条件，不设置联系方式、身份或地点编号",Map.of("taskKey",key,"fields",object(fields,List.of())),"taskKey","fields"));
  tools.add(tool("prepare_task","检查依赖与原业务条件，准备本人草稿预览；不会保存业务申请",Map.of("taskKey",key),"taskKey"));
  tools.add(tool("refresh_task","重新读取本地任务与原业务状态，核对用户的本地修改或确认结果",Map.of("taskKey",key),"taskKey"));
  tools.add(tool("select_venue","按用户目标从当前真实候选中选择一个场地，供本人核对",Map.of("taskKey",key,"optionKey",text(50)),"taskKey","optionKey"));
  var search=new LinkedHashMap<String,Object>();for(String name:List.of("campus","participants","startsAt","endsAt","keyword"))search.put(name,fields.get(name));
  tools.add(tool("revise_reservation","原方案冲突或无候选时查询替代时间或校区；本人仍须核对实际方案",Map.of("taskKey",key,"fields",object(search,List.of())),"taskKey","fields"));
  tools.add(tool("await_user","提出明确缺项问题或等待本人核对当前预览与确认",Map.of("taskKey",key,"question",text(300)),"question"));
  if(submitAllowed)tools.add(tool("submit_task","仅提交已经本人按当前精确原申请快照授权的任务",Map.of("taskKey",key),"taskKey"));
  tools.add(tool("verify_submission","读取原申请，核验实际提交结果和最新状态",Map.of("taskKey",key),"taskKey"));
  tools.add(tool("watch_task","对计划中要求TRACK且已核验提交的本人事项持续跟踪，只在变化时站内提醒",Map.of("taskKey",key),"taskKey"));
  tools.add(tool("link_task_result","将同一业务的已核验前置申请关联到独立跟踪步骤，复用原申请，不新建申请",Map.of("taskKey",key,"sourceTaskKey",key),"taskKey","sourceTaskKey"));
  tools.add(tool("finish","只有所有任务按目标完成且结果已核验时结束",Map.of()));
  return List.copyOf(tools);
 }
}
