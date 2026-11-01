package cn.zzuit.campus.agent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;import java.net.http.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.json.JsonMapper;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:agent-mission-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentMissionHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 @MockitoSpyBean AgentMissionTools missionTools;
 long studentId,venueId;String username="mission_student";
 @BeforeAll void fixture(){
  jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :name,password_hash,'任务测试学生',:name,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username='20260001'").param("name",username).update();
  studentId=jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",username).query(Long.class).single();
  jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'STUDENT')").param("id",studentId).update();
  long reviewer=jdbc.sql("SELECT u.id FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code WHERE u.enabled=TRUE AND rp.permission_code='reservation:review' ORDER BY u.id LIMIT 1").query(Long.class).single();
  for(String code:List.of("MISSION-A","MISSION-B")){
   jdbc.sql("INSERT INTO venue_resource(campus_id,code,name,category,building_name,location_detail,capacity,enabled) VALUES(1,:code,:name,'VENUE','演示楼','任务专用房间',60,TRUE)").param("code",code).param("name","任务测试教室"+code).update();
   long id=jdbc.sql("SELECT id FROM venue_resource WHERE code=:code").param("code",code).query(Long.class).single();
   jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v",id).param("u",reviewer).update();venueId=id;
  }
 }
 @BeforeEach void offline(){doReturn(false).when(model).ready();}
 Map<String,Object> fields(){var day=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4);return Map.of("campus","EAST","startsAt",day+"T09:00","endsAt",day+"T11:00","participants",30,"keyword","任务测试教室","activityTheme","Agent隔离测试活动","activityType","ACADEMIC","contactPhone","13800138000");}
 long session(Browser b)throws Exception{return ok(b.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 String path(long session){return "/api/assistant/sessions/"+session+"/missions";}
 JsonNode start(Browser b,long session,String mode)throws Exception{return ok(b.post(path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备一个场地预约草稿","mode",mode,"externalConsent",mode.equals("DEEPSEEK"),"fields",fields())));}
 Map<String,Object> command(JsonNode view,String action){var body=new HashMap<String,Object>();body.put("requestKey",UUID.randomUUID().toString());body.put("version",view.path("version").asInt());body.put("action",action);return body;}
 JsonNode act(Browser b,long session,JsonNode view,Map<String,Object> command)throws Exception{return ok(b.post(path(session)+"/"+view.path("id").asLong()+"/actions",command));}
 @Test void localTaskChecksToolsWaitsForSelectionThenConfirmationAndVerifiesSavedDraft()throws Exception{
  var user=login(username);long session=session(user);long before=jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE applicant_id=:u").param("u",studentId).query(Long.class).single();
  var task=start(user,session,"LOCAL");assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
  assertThat(task.path("steps").toString()).contains("check_schedule","find_venues","present_options");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE applicant_id=:u").param("u",studentId).query(Long.class).single()).isEqualTo(before);
  var select=command(task,"SELECT");select.put("optionKey",task.path("options").get(0).path("key").asText());task=act(user,session,task,select);
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
  var confirm=command(task,"CONFIRM");confirm.put("confirmed",false);confirm.put("proposalHash",task.path("proposalHash").asText());
  assertThat(user.post(path(session)+"/"+task.path("id").asLong()+"/actions",confirm).statusCode()).isEqualTo(400);
  confirm.put("confirmed",true);task=act(user,session,task,confirm);
  assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("status").asText()).isEqualTo("DRAFT");
  assertThat(task.path("steps").toString()).contains("save_reservation","verify_reservation","finish");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE applicant_id=:u").param("u",studentId).query(Long.class).single()).isEqualTo(before+1);
  assertThat(act(user,session,task,confirm).path("business").path("id").asLong()).isEqualTo(task.path("business").path("id").asLong());
 }
 @Test void modelReceivesSanitizedObservationsAndCanChangeToolOrder()throws Exception{
  doReturn(true).when(model).ready();
  doAnswer(invocation->{
   List<String> allowed=invocation.getArgument(1);Object observations=invocation.getArgument(2);String serialized=JSON.writeValueAsString(observations);
   assertThat(serialized).doesNotContain("任务测试学生","13800138000","任务测试教室","identityNumber","contactPhone");
   String next=allowed.contains("find_venues")?"find_venues":allowed.get(0);
   return new Plan(List.of(new Call(next,JSON.valueToTree(Map.of()))),10,2);
  }).when(model).nextMissionStep(anyString(),anyList(),anyList());
  var user=login(username);var task=start(user,session(user),"DEEPSEEK");
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
  assertThat(task.path("steps").get(0).path("action").asText()).isEqualTo("find_venues");
  assertThat(task.path("steps").get(1).path("action").asText()).isEqualTo("check_schedule");
  verify(model,atLeast(3)).nextMissionStep(anyString(),anyList(),anyList());
 }
 @Test void missingConditionsPauseAndAUserCanReviseAndResume()throws Exception{
  var user=login(username);long session=session(user);
  var task=ok(user.post(path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","下周二下午找30人的教室","mode","LOCAL","externalConsent",false)));
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("missing").toString()).contains("campus","startsAt","endsAt");
  var revise=command(task,"REVISE");revise.put("fields",fields());task=act(user,session,task,revise);
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
 }
 @Test void outOfPolicyModelActionCannotWriteAndIsRecorded()throws Exception{
  doReturn(true).when(model).ready();doReturn(new Plan(List.of(new Call("save_reservation",JSON.valueToTree(Map.of()))),10,2)).when(model).nextMissionStep(anyString(),anyList(),anyList());
  long before=jdbc.sql("SELECT COUNT(*) FROM venue_reservation").query(Long.class).single();var user=login(username);var task=start(user,session(user),"DEEPSEEK");
  assertThat(task.path("stage").asText()).isEqualTo("FAILED");assertThat(task.path("steps").toString()).contains("MODEL_ACTION_REJECTED");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation").query(Long.class).single()).isEqualTo(before);
 }
 @Test void foreignSessionsAndMissionsAreNotAccessible()throws Exception{
  var user=login(username);long session=session(user);var task=start(user,session,"LOCAL");var other=login("20260002");
  assertThat(other.get(path(session)+"/"+task.path("id").asLong()).statusCode()).isEqualTo(404);
  assertThat(other.post(path(session)+"/"+task.path("id").asLong()+"/actions",command(task,"CANCEL")).statusCode()).isEqualTo(404);
 }
 @Test void staleConfirmationAfterVenueChangeRequiresReplanning()throws Exception{
  var user=login(username);long session=session(user);var task=start(user,session,"LOCAL");var option=task.path("options").get(0);
  var select=command(task,"SELECT");select.put("optionKey",option.path("key").asText());task=act(user,session,task,select);
  jdbc.sql("UPDATE venue_resource SET enabled=FALSE,version=version+1 WHERE id=:v").param("v",option.path("venueId").asLong()).update();
  try{var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());
   var changed=act(user,session,task,confirm);assertThat(changed.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(changed.path("business").isNull()).isTrue();
  }finally{jdbc.sql("UPDATE venue_resource SET enabled=TRUE,version=version+1 WHERE id=:v").param("v",option.path("venueId").asLong()).update();}
 }
 java.util.concurrent.CompletableFuture<HttpResponse<String>> asyncPost(Browser user,String path,Map<String,?> body)throws Exception {
  return user.http.sendAsync(user.builder(path).header(user.header,user.token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void replayDuringInitialPlanningCannotAcquireTheSameRunnerTwice()throws Exception {
  var user=login(username);long session=session(user);var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);var first=new java.util.concurrent.atomic.AtomicBoolean(true);
  doAnswer(invocation->{if(first.compareAndSet(true,false)){entered.countDown();if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("planner did not resume");}return invocation.callRealMethod();}).when(missionTools).allowed(any());
  var body=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"goal","准备预约草稿","mode","LOCAL","externalConsent",false,"fields",fields());
  var initial=asyncPost(user,path(session),body);
  try {
   assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   var replay=ok(user.post(path(session),body));assertThat(replay.path("stage").asText()).isEqualTo("RUNNING");
  } finally {resume.countDown();}
  var result=ok(initial.get(10,java.util.concurrent.TimeUnit.SECONDS));assertThat(result.path("stage").asText()).isEqualTo("WAITING_SELECTION");assertThat(result.path("steps").size()).isEqualTo(3);
 }
 @Test void anExecutingTaskProtectsItsParentSessionFromDeletion()throws Exception {
  doReturn(true).when(model).ready();var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
  doAnswer(invocation->{entered.countDown();if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("model did not resume");List<String> allowed=invocation.getArgument(1);return new Plan(List.of(new Call(allowed.get(0),JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());
  var user=login(username);long session=session(user);
  var work=asyncPost(user,path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","准备预约草稿","mode","DEEPSEEK","externalConsent",true,"fields",fields()));
  try {assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(user.send("DELETE","/api/assistant/sessions/"+session,Map.of()).statusCode()).isEqualTo(409);}
  finally{resume.countDown();work.get(15,java.util.concurrent.TimeUnit.SECONDS);}
 }
 @Test void cancellingWhileModelIsRunningStopsLaterActions()throws Exception {
  doReturn(true).when(model).ready();var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
  doAnswer(invocation->{entered.countDown();if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("model did not resume");List<String> allowed=invocation.getArgument(1);return new Plan(List.of(new Call(allowed.get(0),JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());
  var user=login(username);long session=session(user);long before=jdbc.sql("SELECT COUNT(*) FROM venue_reservation").query(Long.class).single();
  var work=asyncPost(user,path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","准备预约草稿","mode","DEEPSEEK","externalConsent",true,"fields",fields()));
  try {
   assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();long id=ok(user.get(path(session))).get(0).path("id").asLong();var task=ok(user.get(path(session)+"/"+id));
   var cancelled=act(user,session,task,command(task,"CANCEL"));assertThat(cancelled.path("stage").asText()).isEqualTo("CANCELLED");assertThat(cancelled.path("steps").toString()).doesNotContain("RUNNING");
  }finally{resume.countDown();work.get(15,java.util.concurrent.TimeUnit.SECONDS);}
  assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation").query(Long.class).single()).isEqualTo(before);
 }
 @Test void interruptedLeaseCanResumeAndTaskBudgetCannotBeBypassed()throws Exception {
  var user=login(username);long session=session(user);var task=start(user,session,"LOCAL");long id=task.path("id").asLong();
  jdbc.sql("UPDATE agent_mission SET stage='RUNNING',runner_token='expired',lease_until=:time WHERE id=:id").param("time",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(2)).param("id",id).update();
  task=ok(user.get(path(session)+"/"+id));assertThat(task.path("stage").asText()).isEqualTo("INTERRUPTED");
  task=act(user,session,task,command(task,"CONTINUE"));assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
  jdbc.sql("UPDATE agent_mission SET stage='PAUSED',step_count=24 WHERE id=:id").param("id",id).update();
  task=ok(user.get(path(session)+"/"+id));task=act(user,session,task,command(task,"CONTINUE"));
  assertThat(task.path("stage").asText()).isEqualTo("FAILED");assertThat(task.path("canContinue").asBoolean()).isFalse();
 }
 @Test void modelTimeoutLeavesRecoverableTraceAndConsentIsRequired()throws Exception {
  doReturn(true).when(model).ready();var user=login(username);long session=session(user);
  assertThat(user.post(path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","准备预约草稿","mode","DEEPSEEK","externalConsent",false,"fields",fields())).statusCode()).isEqualTo(400);
  doThrow(new cn.zzuit.campus.common.ApiException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,"MODEL_TIMEOUT","模型调用超时")).when(model).nextMissionStep(anyString(),anyList(),anyList());
  var task=start(user,session,"DEEPSEEK");assertThat(task.path("stage").asText()).isEqualTo("FAILED");assertThat(task.path("steps").toString()).contains("MODEL_TIMEOUT");
  doAnswer(invocation->{List<String> allowed=invocation.getArgument(1);return new Plan(List.of(new Call(allowed.get(0),JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());
  task=act(user,session,task,command(task,"CONTINUE"));assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
 }
 @Test void taskCanBeDiscoveredByItsRequestKeyAndCancellationDoesNotDependOnAnOldVersion()throws Exception {
  var user=login(username);long session=session(user);String key=UUID.randomUUID().toString();
  var task=ok(user.post(path(session),Map.of("requestKey",key,"goal","准备预约草稿","mode","LOCAL","externalConsent",false,"fields",fields())));
  assertThat(ok(user.get(path(session))).get(0).path("requestKey").asText()).isEqualTo(key);
  var cancel=command(task,"CANCEL");cancel.put("version",0);task=act(user,session,task,cancel);
  assertThat(task.path("stage").asText()).isEqualTo("CANCELLED");
 }
 @Test void noCandidatesAndPersonalConflictsPauseAndCanBeReplanned()throws Exception {
  var user=login(username);long session=session(user);var f=new HashMap<String,Object>(fields());f.put("keyword","绝不匹配的资源");
  var task=ok(user.post(path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","准备预约草稿","mode","LOCAL","externalConsent",false,"fields",f)));
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("message").asText()).contains("没有可用候选");
  var revise=command(task,"REVISE");revise.put("fields",fields());task=act(user,session,task,revise);assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
  long block=jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE applicant_id=:u").param("u",studentId).query(Long.class).single();
  jdbc.sql("INSERT INTO venue_reservation(applicant_id,request_key,venue_id,applicant_name,identity_number,contact_phone,department_name,venue_name,location_detail,activity_theme,expected_participants,starts_at,ends_at,status) VALUES(:u,:key,:v,'任务学生','fixture','fixture','演示部门','冲突场地','本地','本人已有活动',1,:start,:end,'SUBMITTED')").param("u",studentId).param("key","mission-conflict").param("v",venueId).param("start",LocalDateTime.parse(fields().get("startsAt").toString())).param("end",LocalDateTime.parse(fields().get("endsAt").toString())).update();
  try {
   var blocked=start(user,session(user),"LOCAL");assertThat(blocked.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(blocked.path("conflicts").size()).isGreaterThan(0);
   assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE applicant_id=:u").param("u",studentId).query(Long.class).single()).isEqualTo(block+1);
  }finally{jdbc.sql("DELETE FROM venue_reservation WHERE request_key='mission-conflict'").update();}
 }
 @Test void staleVersionWrongHashAndConflictingRetryCannotCreateDraft()throws Exception {
  var user=login(username);long session=session(user);String key=UUID.randomUUID().toString();var body=new HashMap<String,Object>(Map.of("requestKey",key,"goal","准备预约草稿","mode","LOCAL","externalConsent",false,"fields",fields()));
  var task=ok(user.post(path(session),body));body.put("goal","修改预约目标");assertThat(user.post(path(session),body).statusCode()).isEqualTo(409);
  var select=command(task,"SELECT");select.put("optionKey","arbitrary");assertThat(user.post(path(session)+"/"+task.path("id").asLong()+"/actions",select).statusCode()).isEqualTo(409);
  select.put("optionKey",task.path("options").get(0).path("key").asText());task=act(user,session,task,select);
  var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash","a".repeat(64));assertThat(user.post(path(session)+"/"+task.path("id").asLong()+"/actions",confirm).statusCode()).isEqualTo(409);
  confirm.put("proposalHash",task.path("proposalHash").asText());confirm.put("version",0);assertThat(user.post(path(session)+"/"+task.path("id").asLong()+"/actions",confirm).statusCode()).isEqualTo(409);
  assertThat(task.path("business").isNull()).isTrue();
 }
 @Test void anonymousOrWrongRoleCannotStartMissionsAndMalformedModelArgumentsFailClosed()throws Exception {
  var user=login(username);long session=session(user);assertThat(new Browser().get(path(session)).statusCode()).isEqualTo(401);
  var finance=login("finance1001");long staffSession=session(finance);assertThat(finance.post(path(staffSession),Map.of("requestKey",UUID.randomUUID().toString(),"goal","准备预约草稿","mode","LOCAL","externalConsent",false,"fields",fields())).statusCode()).isEqualTo(403);
  doReturn(true).when(model).ready();doReturn(new Plan(List.of(new Call("check_schedule",JSON.valueToTree(Map.of("userId",2)))),10,2)).when(model).nextMissionStep(anyString(),anyList(),anyList());
  assertThat(start(user,session,"DEEPSEEK").path("stage").asText()).isEqualTo("FAILED");
 }
    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return JSON.readTree(response.body());
    }
    private Browser login(String username) throws Exception {
        var browser = new Browser(); browser.csrf();
        ok(browser.post("/api/auth/login", Map.of("username", username, "password", "Demo@2026"))); browser.csrf(); return browser;
    }
    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); String header, token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception { var data = JSON.readTree(get("/api/auth/csrf").body()); header = data.path("headerName").asText(); token = data.path("token").asText(); }
        HttpResponse<String> post(String path, Map<String, ?> body) throws Exception { return send("POST", path, body); }
        HttpResponse<String> send(String method, String path, Map<String, ?> body) throws Exception {
            return http.send(builder(path).header(header, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
