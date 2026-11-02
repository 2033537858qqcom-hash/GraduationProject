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
  tools.add(tool("set_plan","创建或修订任务计划和依赖；保留已存在的任务及业务历史",Map.of("tasks",Map.of("type","array","items",plan,"minItems",1,"maxItems",6)),"tasks"));
  tools.add(tool("read_guide","读取指定业务有效公开指南，作为规划数据",Map.of("kind",choice(kinds)),"kind"));
  var fields=new LinkedHashMap<String,Object>();
  fields.put("campus",choice(List.of("EAST","WEST","ALL")));fields.put("participants",Map.of("type","integer","minimum",1,"maximum",10000));
  fields.put("startsAt",text(19));fields.put("endsAt",text(19));fields.put("keyword",text(40));fields.put("activityTheme",text(200));fields.put("activityType",choice(List.of("ACADEMIC","SPORTS","CULTURE","OTHER")));
  fields.put("purpose",text(300));fields.put("reasonType",choice(List.of("PERSONAL","SICK","OTHER")));fields.put("reason",text(2000));
  fields.put("startDate",text(10));fields.put("startTime",text(5));fields.put("endDate",text(10));fields.put("endTime",text(5));
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
  tools.add(tool("finish","只有所有任务按目标完成且结果已核验时结束",Map.of()));
  return List.copyOf(tools);
 }
}
