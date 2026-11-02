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
 "spring.datasource.url=jdbc:h2:mem:agent-goal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalHttpTest {
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

 @Test void modelChoosesGuideBeforePlanningAndRealDraftNeedsHumanConfirmation()throws Exception{
  decisions.add(call("read_guide",Map.of("kind","CERTIFICATE")));prepareCertificate();
  var user=login("20260001");long s=session(user),before=count();var request=input();var goal=ok(user.post(path(s),request));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");assertThat(goal.path("tasks").size()).isEqualTo(1);
  assertThat(goal.path("events").get(0).path("tool").asText()).isEqualTo("read_guide");
  assertThat(goal.path("tasks").get(0).path("mission").path("certificateFields").path("purpose").asText()).isEqualTo("实习单位核验在校身份");
  assertThat(count()).isEqualTo(before);assertThat(ok(user.post(path(s),request)).path("id")).isEqualTo(goal.path("id"));
  var mission=goal.path("tasks").get(0).path("mission");saveChild(user,s,mission);
  decisions.add(call("refresh_task",Map.of("taskKey","certificate")));decisions.add(call("finish",Map.of()));
  goal=act(user,s,goal,action(goal,"CONTINUE"));assertThat(goal.path("stage").asText()).isEqualTo("DONE");assertThat(count()).isEqualTo(before+1);
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("DRAFT");
 }
 @Test void mixedBusinessGoalBuildsDependentPlanRatherThanRejectingMixedIntent()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("proof","CERTIFICATE",List.of()),task("leave","LEAVE",List.of("proof"))))));
  decisions.add(call("prepare_task",Map.of("taskKey","leave")));
  var user=login("20260001");var request=input();request.put("goal","先准备实习证明，然后帮我准备请假草稿");var goal=ok(user.post(path(session(user)),request));
  assertThat(goal.path("tasks").size()).isEqualTo(2);assertThat(goal.path("tasks").get(1).path("dependsOn").toString()).contains("proof");
  assertThat(goal.path("events").toString()).contains("GOAL_DEPENDENCY_PENDING");
  assertThat(goal.path("tasks").get(1).path("mission").isNull()).isTrue();
 }
 @Test void cyclicPlanAndInventedIdentityFieldsAreRejectedAndCanReplan()throws Exception{
  long before=count();
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("a","LEAVE",List.of("b")),task("b","CERTIFICATE",List.of("a"))))));
  certificatePlan();decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("studentId",999,"purpose","伪造用途"))));
  certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("events").toString()).contains("GOAL_PLAN_INVALID","GOAL_FIELDS_INVALID");assertThat(goal.path("tasks").size()).isEqualTo(1);assertThat(count()).isEqualTo(before);
 }
 @Test void cannotFinishWhileAnyBusinessTaskHasNotBeenSavedAndVerified()throws Exception{
  certificatePlan();decisions.add(call("finish",Map.of()));var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(goal.path("events").toString()).contains("GOAL_NOT_COMPLETE");
 }
 @Test void localIdentityAndLocallyEditedPurposeNeverBecomeModelContext()throws Exception{
  prepareCertificate();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));var mission=goal.path("tasks").get(0).path("mission");
  var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","REVISE","certificateFields",Map.of("purpose","PRIVATE_LOCAL_PURPOSE_00991")));
  ok(user.post("/api/assistant/sessions/"+s+"/missions/"+mission.path("id").asLong()+"/actions",body));
  strategy=context->{assertThat(context.toString()).doesNotContain("PRIVATE_LOCAL_PURPOSE_00991","identityNumber","studentName","contactPhone","classGroupId");return call("await_user",Map.of("taskKey","certificate","question","请核对当前预览。"));};
  goal=act(user,s,goal,action(goal,"CONTINUE"));assertThat(goal.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
 }
 @Test void consentForeignAccessAndCancelledGoalAreEnforced()throws Exception{
  var user=login("20260001");long s=session(user);var request=input();request.put("externalConsent",false);assertThat(user.post(path(s),request).statusCode()).isEqualTo(400);
  prepareCertificate();var goal=ok(user.post(path(s),input()));var foreign=login("20260002");assertThat(foreign.get(path(s)+"/"+goal.path("id").asLong()).statusCode()).isEqualTo(404);
  goal=act(user,s,goal,action(goal,"CANCEL"));assertThat(goal.path("stage").asText()).isEqualTo("CANCELLED");
  assertThat(user.post(path(s)+"/"+goal.path("id").asLong()+"/actions",action(goal,"CONTINUE")).statusCode()).isEqualTo(409);
 }
 @Test void revisionRetainsBusinessHistoryAndReplacesGoalInstructions()throws Exception{
  certificatePlan();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));
  var revision=action(goal,"REVISE");revision.put("message","暂时只准备证明，先核对办理指南");revision.put("externalConsent",true);
  decisions.add(call("read_guide",Map.of("kind","CERTIFICATE")));goal=act(user,s,goal,revision);
  assertThat(goal.path("instructions").toString()).contains("暂时只准备证明");assertThat(goal.path("tasks").size()).isEqualTo(1);
  assertThat(goal.path("events").toString()).contains("read_guide");
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
 }
}
