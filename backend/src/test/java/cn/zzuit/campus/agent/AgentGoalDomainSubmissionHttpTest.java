package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:agent-goal-domain-submit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.goals.monitor.enabled=false","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalDomainSubmissionHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 String desiredTarget="SUBMIT";
 final Deque<Call> decisions=new ArrayDeque<>();
 Function<JsonNode,Call> strategy;
 @BeforeEach void planning(){
  desiredTarget="SUBMIT";decisions.clear();strategy=context->decisions.isEmpty()?call("await_user",Map.of("question","请核对当前任务并补充缺少的资料。")):decisions.removeFirst();
  doReturn(true).when(model).ready();
  doAnswer(invocation->new Plan(List.of(strategy.apply(invocation.getArgument(2))),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
 }
 Call call(String name,Map<String,?> fields){return new Call(name,JSON.valueToTree(fields));}
 Map<String,Object> task(String key,String kind,List<String> dependencies){return Map.of("key",key,"kind",kind,"label","准备本人业务草稿","target",desiredTarget,"dependsOn",dependencies);}
 void certificatePlan(){decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of())))));}
 void certificateFields(){decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","实习单位核验在校身份"))));}
 void prepareCertificate(){certificatePlan();certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));decisions.add(call("await_user",Map.of("taskKey","certificate","question","请核对本人登记资料及用途后确认保存。")));}
 Map<String,Object> input(){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备实习单位核验身份用的在校证明草稿","externalConsent",true));}
 String path(long session){return "/api/assistant/sessions/"+session+"/goals";}
 long session(Browser browser)throws Exception{return ok(browser.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 Map<String,Object> action(JsonNode goal,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",goal.path("version").asInt(),"action",action));}
 JsonNode act(Browser browser,long s,JsonNode goal,Map<String,Object> input)throws Exception{return ok(browser.post(path(s)+"/"+goal.path("id").asLong()+"/actions",input));}

 long venue,location;
 @BeforeAll void fixtures(){
  long reviewer=jdbc.sql("SELECT u.id FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code WHERE u.enabled=TRUE AND rp.permission_code='reservation:review' ORDER BY u.id LIMIT 1").query(Long.class).single();
  jdbc.sql("INSERT INTO venue_resource(campus_id,code,name,category,building_name,location_detail,capacity,enabled) VALUES(1,'GOAL-SUBMISSION','目标提交场地','VENUE','演示楼','提交验收房间',60,TRUE)").update();venue=jdbc.sql("SELECT id FROM venue_resource WHERE code='GOAL-SUBMISSION'").query(Long.class).single();jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v",venue).param("u",reviewer).update();
  jdbc.sql("INSERT INTO repair_location(campus_id,code,name,kind,detail_hint,provenance,enabled) VALUES(1,'GOAL-SUBMISSION','目标提交报修地点','CAMPUS_PUBLIC','演示地点','DEMO',TRUE)").update();location=jdbc.sql("SELECT id FROM repair_location WHERE code='GOAL-SUBMISSION'").query(Long.class).single();
 }
 @Test void reservationGoalSavesThenExplicitlySubmitsTheSameOwnedRequest()throws Exception{
  var fields=Map.<String,Object>of("campus","EAST","startsAt","2095-11-12T09:00","endsAt","2095-11-12T11:00","participants",30,"keyword","目标提交场地","activityTheme","学习交流活动","activityType","ACADEMIC");var local=new HashMap<String,Object>(fields);local.put("contactPhone","13900139000");var goal=complete("RESERVATION",fields,local,"fields");
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("targetPath").asText()).startsWith("/portal/reservations");
 }
 @Test void leaveGoalKeepsPrivateContactLocalAndSubmitsThroughOrdinaryRules()throws Exception{
  String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(9).toString();var fields=Map.<String,Object>of("reasonType","PERSONAL","reason","参加实习单位线下核验","startDate",day,"startTime","09:00","endDate",day,"endTime","17:00");var local=new HashMap<String,Object>(fields);local.put("contact","13900139000");var goal=complete("LEAVE",fields,local,"leaveFields");
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("targetPath").asText()).startsWith("/portal/leave");
 }
 @Test void repairGoalUsesLocallySelectedLocationAndRealDispatcherEligibility()throws Exception{
  var fields=Map.<String,Object>of("category","ELECTRICITY","title","照明故障","description","灯具无法开启","locationDetail","东侧走廊");var local=new HashMap<String,Object>(fields);local.put("locationId",location);local.put("contact","13900139000");local.put("contactWindow","白天联系");var goal=complete("REPAIR",fields,local,"repairFields");
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("targetPath").asText()).startsWith("/repairs/mine");
 }
 JsonNode complete(String kind,Map<String,Object> fields,Map<String,Object> local,String fieldName)throws Exception{
  var originalStrategy=strategy;strategy=context->{assertThat(context.toString()).doesNotContain("13900139000","identityNumber","studentName","contactPhone");return originalStrategy.apply(context);};
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("item",kind,List.of())))));decisions.add(call("set_task_fields",Map.of("taskKey","item","fields",fields)));decisions.add(call("prepare_task",Map.of("taskKey","item")));decisions.add(call("await_user",Map.of("taskKey","item","question","请在本地核对实际资料。")));
  var user=login("20260001");long s=session(user);var request=input();request.put("goal","准备并提交本人"+kind+"申请");var goal=ok(user.post(path(s),request));var mission=goal.path("tasks").get(0).path("mission");
  if(mission.path("business").isMissingNode()||mission.path("business").isNull()){
   mission=ok(user.post("/api/assistant/sessions/"+s+"/missions/"+mission.path("id").asLong()+"/actions",Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","REVISE",fieldName,local)));
   if(mission.path("stage").asText().equals("WAITING_SELECTION"))mission=ok(user.post("/api/assistant/sessions/"+s+"/missions/"+mission.path("id").asLong()+"/actions",Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","SELECT","optionKey",mission.path("options").get(0).path("key").asText())));
   saveChild(user,s,mission);
  }
  goal=ok(user.get(path(s)+"/"+goal.path("id").asLong()));
  if("SUBMITTED".equals(goal.path("tasks").get(0).path("mission").path("business").path("status").asText())||goal.path("tasks").get(0).path("submissionVerified").asBoolean()){
   assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return goal;
  }
  var task=goal.path("tasks").get(0);
  if(task.path("submissionPreview").isMissingNode()||task.path("submissionPreview").isNull()||!task.path("submissionPreview").path("canSubmit").asBoolean()){
   decisions.add(call("refresh_task",Map.of("taskKey","item")));decisions.add(call("await_user",Map.of("taskKey","item","question","请核对原申请并确认提交。")));goal=act(user,s,goal,action(goal,"CONTINUE"));task=goal.path("tasks").get(0);
  }
  assertThat(task.path("submissionPreview").path("canSubmit").asBoolean()).as(task.path("submissionPreview").toString()).isTrue();
  decisions.add(call("submit_task",Map.of("taskKey","item")));decisions.add(call("verify_submission",Map.of("taskKey","item")));decisions.add(call("finish",Map.of()));var consent=action(goal,"AUTHORIZE_SUBMIT");consent.put("taskKey","item");consent.put("confirmed",true);consent.put("proposalHash",task.path("submissionPreview").path("proposalHash").asText());goal=act(user,s,goal,consent);
  assertThat(goal.path("stage").asText()).as(goal.toString()).isIn("DONE","MONITORING");assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return goal;
 }
 long count(){return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single();}
 void saveChild(Browser user,long session,JsonNode mission)throws Exception{if(!mission.path("business").isMissingNode()&&!mission.path("business").isNull())return;ok(user.post("/api/assistant/sessions/"+session+"/missions/"+mission.path("id").asLong()+"/actions",Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","CONFIRM","confirmed",true,"proposalHash",mission.path("proposalHash").asText())));}
 JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return JSON.readTree(response.body());}
 Browser login(String name)throws Exception{var b=new Browser();b.csrf();ok(b.post("/api/auth/login",Map.of("username",name,"password","Demo@2026")));b.csrf();return b;}
 class Browser{
  final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
  HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
  HttpResponse<String> get(String path)throws Exception{return http.send(request(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
  void csrf()throws Exception{var data=JSON.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
  HttpResponse<String> post(String path,Map<String,?> body)throws Exception{return http.send(request(path).header(header,token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
  HttpResponse<String> delete(String path)throws Exception{return http.send(request(path).header(header,token).DELETE().build(),HttpResponse.BodyHandlers.ofString());}
 }
}
