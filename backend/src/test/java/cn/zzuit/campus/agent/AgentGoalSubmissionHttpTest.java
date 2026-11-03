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
 "spring.datasource.url=jdbc:h2:mem:agent-goal-submission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.goals.monitor.enabled=false","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalSubmissionHttpTest {
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

 @Test void explicitlyAuthorizedSubmissionIsVerifiedAndRetryDoesNotSubmitTwice()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal)){assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return;}
  var task=goal.path("tasks").get(0);assertThat(task.path("submissionPreview").path("proposalHash").asText()).hasSize(64);
  decisions.add(call("submit_task",Map.of("taskKey","certificate")));decisions.add(call("verify_submission",Map.of("taskKey","certificate")));decisions.add(call("finish",Map.of()));
  var authorization=authorize(goal);goal=act(user,s,goal,authorization);assertThat(goal.path("stage").asText()).isEqualTo("DONE");
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");
  assertThat(goal.path("tasks").get(0).path("submissionVerified").asBoolean()).isTrue();
  var repeated=act(user,s,goal,authorization);assertThat(repeated.path("id")).isEqualTo(goal.path("id"));
  long id=goal.path("tasks").get(0).path("mission").path("business").path("id").asLong();
  assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_revision WHERE request_id=:id AND action='SUBMITTED'").param("id",id).query(Long.class).single()).isEqualTo(1);
 }
 @Test void plannerCannotSubmitAnUnconfirmedDraft()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal)){assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return;}
  decisions.add(call("submit_task",Map.of("taskKey","certificate")));goal=act(user,s,goal,action(goal,"CONTINUE"));
  assertThat(goal.path("events").toString()).contains("GOAL_TOOL_REJECTED");assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("DRAFT");
  var noConsent=authorize(goal);noConsent.put("confirmed",false);assertThat(user.post(path(s)+"/"+goal.path("id").asLong()+"/actions",noConsent).statusCode()).isEqualTo(400);
 }
 @Test void changedBusinessSnapshotRejectsPreviousSubmissionAuthorization()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal)){assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return;}
  var authorization=authorize(goal);
  long id=goal.path("tasks").get(0).path("mission").path("business").path("id").asLong();jdbc.sql("UPDATE certificate_request SET purpose='原页面已修改',version=version+1 WHERE id=:id").param("id",id).update();
  assertThat(user.post(path(s)+"/"+goal.path("id").asLong()+"/actions",authorization).statusCode()).isEqualTo(409);
  assertThat(jdbc.sql("SELECT status FROM certificate_request WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("DRAFT");
 }
 @Test void changedGuideOrEligibilityRequiresFreshSubmissionConfirmation()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal)){assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return;}
  var authorization=authorize(goal);
  jdbc.sql("UPDATE agent_guide SET version=version+1 WHERE slug='certificate'").update();
  try{assertThat(user.post(path(s)+"/"+goal.path("id").asLong()+"/actions",authorization).statusCode()).isEqualTo(409);}
  finally{jdbc.sql("UPDATE agent_guide SET version=version-1 WHERE slug='certificate'").update();}
  jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE username='affairs1001'").update();
  try{var refreshed=ok(user.get(path(s)+"/"+goal.path("id").asLong()));assertThat(refreshed.path("tasks").get(0).path("submissionPreview").path("canSubmit").asBoolean()).isFalse();assertThat(user.post(path(s)+"/"+goal.path("id").asLong()+"/actions",authorization).statusCode()).isEqualTo(409);}
  finally{jdbc.sql("UPDATE app_user SET enabled=TRUE WHERE username='affairs1001'").update();}
 }
 @Test void editingBetweenAuthorizationAndExecutionCannotSubmitChangedData()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal)){assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");return;}
  long id=goal.path("tasks").get(0).path("mission").path("business").path("id").asLong();
  var once=new java.util.concurrent.atomic.AtomicBoolean();
  strategy=context->{if(!once.getAndSet(true)){jdbc.sql("UPDATE certificate_request SET purpose='另一窗口修改',version=version+1 WHERE id=:id").param("id",id).update();return call("submit_task",Map.of("taskKey","certificate"));}return call("await_user",Map.of("taskKey","certificate","question","原申请已变化，请重新核对提交。"));};
  goal=act(user,s,goal,authorize(goal));assertThat(goal.path("events").toString()).contains("GOAL_SUBMISSION_CHANGED");assertThat(jdbc.sql("SELECT status FROM certificate_request WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("DRAFT");
 }
 @Test void submissionAuthorizationAlsoBindsTheRequestedTaskOutcome()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal))return;
  var authorization=authorize(goal);long id=goal.path("id").asLong();
  var state=JSON.readValue(jdbc.sql("SELECT state_json FROM agent_goal WHERE id=:id").param("id",id).query(String.class).single(),AgentGoalModels.State.class);state.tasks.get(0).target="TRACK";
  jdbc.sql("UPDATE agent_goal SET state_json=:state WHERE id=:id").param("state",JSON.writeValueAsString(state)).param("id",id).update();
  assertThat(user.post(path(s)+"/"+id+"/actions",authorization).statusCode()).isEqualTo(409);
 }
 @Test void genuineHumanAuthorizationHasAnExplicitObservationForThePlanner()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal))return;
  var observed=new java.util.concurrent.atomic.AtomicReference<JsonNode>();
  strategy=context->{observed.set(context);return call("await_user",Map.of("taskKey","certificate","question","测试回执已读取。"));};act(user,s,goal,authorize(goal));
  assertThat(observed.get().path("observations").toString()).contains("SUBMISSION_AUTHORIZED","human_authorization");assertThat(observed.get().path("tasks").get(0).path("submissionAuthorized").asBoolean()).isTrue();
 }
 @Test void cancellingAPendingAuthorizedGoalClearsItsUnusedSubmissionAuthority()throws Exception{
  var user=login("20260001");long s=session(user);var goal=savedGoal(user,s);
  if(alreadySubmitted(goal)){goal=act(user,s,goal,action(goal,"CANCEL"));assertThat(goal.path("stage").asText()).isIn("CANCELLED","DONE");return;}
  goal=act(user,s,goal,authorize(goal));goal=act(user,s,goal,action(goal,"CANCEL"));
  assertThat(goal.path("stage").asText()).isEqualTo("CANCELLED");assertThat(goal.path("tasks").get(0).path("submissionHash").isNull()).isTrue();assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("DRAFT");
 }
 JsonNode savedGoal(Browser user,long s)throws Exception{
  prepareCertificate();var goal=ok(user.post(path(s),input()));saveChild(user,s,goal.path("tasks").get(0).path("mission"));
  goal=ok(user.get(path(s)+"/"+goal.path("id").asLong()));
  if(Set.of("DONE","MONITORING").contains(goal.path("stage").asText())||"SUBMITTED".equals(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()))return goal;
  decisions.add(call("refresh_task",Map.of("taskKey","certificate")));decisions.add(call("await_user",Map.of("taskKey","certificate","question","请核对原申请后确认提交。")));
  return act(user,s,goal,action(goal,"CONTINUE"));
 }
 boolean alreadySubmitted(JsonNode goal){return "SUBMITTED".equals(goal.path("tasks").get(0).path("mission").path("business").path("status").asText())||Set.of("DONE","MONITORING").contains(goal.path("stage").asText());}
 Map<String,Object> authorize(JsonNode goal){var body=action(goal,"AUTHORIZE_SUBMIT");body.put("taskKey","certificate");body.put("confirmed",true);body.put("proposalHash",goal.path("tasks").get(0).path("submissionPreview").path("proposalHash").asText());return body;}
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
