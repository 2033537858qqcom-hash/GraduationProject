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
 "spring.datasource.url=jdbc:h2:mem:agent-goal-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalRecoveryHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 final Deque<Call> decisions=new ArrayDeque<>();
 Function<JsonNode,Call> strategy;
 @BeforeEach void planning(){
  decisions.clear();strategy=context->decisions.isEmpty()?call("await_user",Map.of("question","请核对当前任务并补充缺少的资料。")):decisions.removeFirst();
  doReturn(true).when(model).ready();
  doAnswer(invocation->new Plan(List.of(strategy.apply(invocation.getArgument(2))),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
 }
 Call call(String name,Map<String,?> fields){return new Call(name,JSON.valueToTree(fields));}
 Map<String,Object> task(String key,String kind,List<String> dependencies){return Map.of("key",key,"kind",kind,"label","准备本人业务草稿","target","DRAFT","dependsOn",dependencies);}
 void certificatePlan(){decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of())))));}
 void certificateFields(){decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","实习单位核验在校身份"))));}
 void prepareCertificate(){certificatePlan();certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));decisions.add(call("await_user",Map.of("taskKey","certificate","question","请核对本人登记资料及用途后确认保存。")));}
 Map<String,Object> input(){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备实习单位核验身份用的在校证明草稿","externalConsent",true));}
 String path(long session){return "/api/assistant/sessions/"+session+"/goals";}
 long session(Browser browser)throws Exception{return ok(browser.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 Map<String,Object> action(JsonNode goal,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",goal.path("version").asInt(),"action",action));}
 JsonNode act(Browser browser,long s,JsonNode goal,Map<String,Object> input)throws Exception{return ok(browser.post(path(s)+"/"+goal.path("id").asLong()+"/actions",input));}

 @Autowired cn.zzuit.campus.reservation.VenueReservationService reservations;
 @Autowired cn.zzuit.campus.identity.IdentityService identities;
 final List<Long> venues=new ArrayList<>();
 @BeforeAll void rooms(){
  long reviewer=jdbc.sql("SELECT u.id FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code WHERE u.enabled=TRUE AND rp.permission_code='reservation:review' ORDER BY u.id LIMIT 1").query(Long.class).single();
  for(int capacity:List.of(30,60)){String code="GOAL-RECOVERY-"+capacity;jdbc.sql("INSERT INTO venue_resource(campus_id,code,name,category,building_name,location_detail,capacity,enabled) VALUES(1,:code,:name,'VENUE','演示楼','独立验收场地',:capacity,TRUE)").param("code",code).param("name","自主恢复场地"+capacity).param("capacity",capacity).update();long id=jdbc.sql("SELECT id FROM venue_resource WHERE code=:code").param("code",code).query(Long.class).single();venues.add(id);jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v",id).param("u",reviewer).update();}
 }
 Map<String,Object> bookingFields(int days){String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(days).toString();return Map.of("campus","EAST","startsAt",day+"T09:00","endsAt",day+"T11:00","participants",30,"keyword","自主恢复场地","activityTheme","学习交流活动","activityType","ACADEMIC");}
 long block(String username,long venue,Map<String,Object> fields){var principal=(cn.zzuit.campus.identity.CampusPrincipal)identities.loadUserByUsername(username);return reservations.submit(principal,new cn.zzuit.campus.reservation.ReservationDtos.CreateReservationCommand(UUID.randomUUID().toString(),venue,"隔离验收占用","ACADEMIC",30,"",java.time.LocalDateTime.parse(fields.get("startsAt").toString()),java.time.LocalDateTime.parse(fields.get("endsAt").toString()),"13800138000")).id();}
 void reservationPlan(){decisions.add(call("set_plan",Map.of("tasks",List.of(task("room","RESERVATION",List.of())))));}
 @Test void actualNoAvailabilityFeedsAnAlternativeQueryAndCapacityBasedChoice()throws Exception{
  var fields=bookingFields(15);for(long venue:venues)block("20260002",venue,fields);
  strategy=context->{
   var tasks=context.path("tasks");if(tasks.isEmpty())return call("set_plan",Map.of("tasks",List.of(task("room","RESERVATION",List.of()))));
   var task=tasks.get(0);String observations=context.path("observations").toString();
   if(task.path("stage").asText().equals("NOT_PREPARED"))return observations.contains("set_task_fields")?call("prepare_task",Map.of("taskKey","room")):call("set_task_fields",Map.of("taskKey","room","fields",fields));
   if(task.path("stage").asText().equals("WAITING_INPUT")){String day=fields.get("startsAt").toString().substring(0,10);return call("revise_reservation",Map.of("taskKey","room","fields",Map.of("startsAt",day+"T12:00","endsAt",day+"T14:00")));}
   if(task.path("stage").asText().equals("WAITING_SELECTION")){JsonNode best=null;for(var option:task.path("options"))if(best==null||option.path("capacity").asInt()<best.path("capacity").asInt())best=option;return call("select_venue",Map.of("taskKey","room","optionKey",best.path("key").asText()));}
   return call("await_user",Map.of("taskKey","room","question","上午已满，已核对下午可用方案，请确认实际时段与场地。"));
  };
  var user=login("20260001");var request=input();request.put("goal","东校区30人学习交流，上午满了可以改为12点至14点，选容量足够且最小的场地");var goal=ok(user.post(path(session(user)),request));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");assertThat(goal.path("events").toString()).contains("revise_reservation","select_venue");var mission=goal.path("tasks").get(0).path("mission");assertThat(mission.path("fields").path("startsAt").asText()).endsWith("T12:00");
  String chosen=mission.path("selectedKey").asText();for(var option:mission.path("options"))if(option.path("key").asText().equals(chosen))assertThat(option.path("capacity").asInt()).isEqualTo(30);
  assertThat(mission.path("business").isNull()).isTrue();
 }
 @Test void refreshingAfterExternalConflictRemovalActuallyRechecksSources()throws Exception{
  var fields=bookingFields(16);long conflict=block("20260001",venues.get(0),fields);reservationPlan();decisions.add(call("set_task_fields",Map.of("taskKey","room","fields",fields)));decisions.add(call("prepare_task",Map.of("taskKey","room")));decisions.add(call("await_user",Map.of("taskKey","room","question","当前日程存在冲突。")));
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));assertThat(goal.path("tasks").get(0).path("mission").path("stage").asText()).isEqualTo("WAITING_INPUT");
  reservations.withdraw((cn.zzuit.campus.identity.CampusPrincipal)identities.loadUserByUsername("20260001"),conflict);
  decisions.add(call("refresh_task",Map.of("taskKey","room")));decisions.add(call("await_user",Map.of("taskKey","room","question","请核对重新读取的候选。")));goal=act(user,s,goal,action(goal,"CONTINUE"));
  assertThat(goal.path("tasks").get(0).path("mission").path("stage").asText()).isEqualTo("WAITING_SELECTION");assertThat(goal.path("tasks").get(0).path("mission").path("options").size()).isEqualTo(2);
 }
 @Test void explicitSpaceSeparatedLocalTimesAreCanonicalizedBeforeQuerying()throws Exception{
  var fields=new HashMap<String,Object>(bookingFields(18));String expected=fields.get("startsAt").toString();fields.put("startsAt",expected.replace('T',' '));fields.put("endsAt",fields.get("endsAt").toString().replace('T',' '));
  reservationPlan();decisions.add(call("set_task_fields",Map.of("taskKey","room","fields",fields)));decisions.add(call("prepare_task",Map.of("taskKey","room")));
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));var mission=goal.path("tasks").get(0).path("mission");
  assertThat(mission.path("fields").path("startsAt").asText()).isEqualTo(expected);assertThat(mission.path("stage").asText()).isEqualTo("WAITING_SELECTION");assertThat(mission.path("options").size()).isEqualTo(2);
 }
 @Test void ambiguousTimeIsRejectedAsAFormatIssueInsteadOfBeingReportedAsUpdated()throws Exception{
  var fields=new HashMap<String,Object>(bookingFields(19));fields.put("startsAt","明天下午");reservationPlan();decisions.add(call("set_task_fields",Map.of("taskKey","room","fields",fields)));
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));assertThat(goal.path("events").toString()).contains("GOAL_TIME_FORMAT");assertThat(goal.path("tasks").get(0).path("mission").isNull()).isTrue();
 }
 @Test void anAlreadyPreparedBlockedTaskDoesNotPretendToPrepareAgain()throws Exception{
  var fields=bookingFields(22);for(long venue:venues)block("20260002",venue,fields);
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("room","RESERVATION",List.of()),task("proof","CERTIFICATE",List.of())))));decisions.add(call("set_task_fields",Map.of("taskKey","room","fields",fields)));decisions.add(call("prepare_task",Map.of("taskKey","room")));decisions.add(call("prepare_task",Map.of("taskKey","room")));
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));assertThat(goal.path("events").toString()).contains("GOAL_TASK_ALREADY_PREPARED");
 }
 @Test void noCandidateFactsRemainExplicitAndRepeatedUnchangedReadsPauseForInput()throws Exception{
  var fields=bookingFields(23);for(long venue:venues)block("20260002",venue,fields);reservationPlan();decisions.add(call("set_task_fields",Map.of("taskKey","room","fields",fields)));decisions.add(call("prepare_task",Map.of("taskKey","room")));decisions.add(call("await_user",Map.of("taskKey","room","question","当前没有候选。")));
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));var captured=new java.util.concurrent.atomic.AtomicReference<JsonNode>();strategy=context->{captured.set(context);return call("refresh_task",Map.of("taskKey","room"));};goal=act(user,s,goal,action(goal,"CONTINUE"));
  assertThat(captured.get().path("tasks").get(0).path("queryStatus").asText()).isEqualTo("NO_CANDIDATES");assertThat(captured.get().path("tasks").get(0).path("missing").size()).isZero();
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(goal.path("events").toString()).contains("NO_CHANGE");
 }
 @Test void explicitReservationDateAndTimePartsCombineWithoutInventingTheEndDate()throws Exception{
  var fields=new HashMap<String,Object>(bookingFields(25));String start=fields.remove("startsAt").toString(),end=fields.remove("endsAt").toString();fields.put("startDate",start.substring(0,10));fields.put("startTime","09:00");fields.put("endDate",end.substring(0,10));fields.put("endTime","11:00");
  reservationPlan();decisions.add(call("set_task_fields",Map.of("taskKey","room","fields",fields)));decisions.add(call("prepare_task",Map.of("taskKey","room")));var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));assertThat(goal.path("tasks").get(0).path("mission").path("fields").path("startsAt").asText()).isEqualTo(start);assertThat(goal.path("tasks").get(0).path("mission").path("stage").asText()).isEqualTo("WAITING_SELECTION");
 }
 long count(){return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single();}
 void saveChild(Browser user,long session,JsonNode mission)throws Exception{ok(user.post("/api/assistant/sessions/"+session+"/missions/"+mission.path("id").asLong()+"/actions",Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","CONFIRM","confirmed",true,"proposalHash",mission.path("proposalHash").asText())));}
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
