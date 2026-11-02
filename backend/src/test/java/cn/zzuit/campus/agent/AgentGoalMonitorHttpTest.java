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
 "spring.datasource.url=jdbc:h2:mem:agent-goal-monitor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.goals.monitor.enabled=false","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalMonitorHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 @Autowired AgentGoalMonitor monitor;
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

 @Test void followsRealReviewChangesQuietlyAndCompletesAtTerminalOutcome()throws Exception{
  var user=login("20260001");long s=session(user);var goal=watchingGoal(user,s);long id=businessId(goal),before=notices(id);clearInvocations(model);
  due(goal);monitor.check();assertThat(notices(id)).isEqualTo(before);
  decide(id,"RETURN");due(goal);assertThat(monitor.check()).as("本次真实审核变化").isEqualTo(1);goal=ok(user.get(path(s)+"/"+goal.path("id").asLong()));
  assertThat(goal.path("stage").asText()).isEqualTo("MONITORING");assertThat(goal.path("tasks").get(0).path("lastBusinessStatus").asText()).isEqualTo("RETURNED");assertThat(notices(id)).isEqualTo(before+1);
  due(goal);monitor.check();assertThat(notices(id)).isEqualTo(before+1);
  var original=ok(user.get("/api/certificates/mine/"+id));ok(user.post("/api/certificates/mine/"+id+"/submit",Map.of("version",original.path("version").asInt(),"comment","本人核对后重新提交")));
  decide(id,"REJECT");due(goal);monitor.check();goal=ok(user.get(path(s)+"/"+goal.path("id").asLong()));
  assertThat(goal.path("stage").asText()).isEqualTo("DONE");assertThat(goal.path("tasks").get(0).path("trackingComplete").asBoolean()).isTrue();assertThat(notices(id)).isEqualTo(before+2);
  verify(model,never()).nextGoalStep(anyString(),anyList(),any());
 }
 @Test void cancellingGoalStopsMonitoringWithoutWithdrawingTheApplication()throws Exception{
  var user=login("20260001");long s=session(user);var goal=watchingGoal(user,s);long id=businessId(goal),before=notices(id);
  goal=act(user,s,goal,action(goal,"CANCEL"));decide(id,"RETURN");due(goal);monitor.check();
  assertThat(notices(id)).isEqualTo(before);assertThat(goal.path("stage").asText()).isEqualTo("CANCELLED");
  assertThat(jdbc.sql("SELECT status FROM certificate_request WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("RETURNED");
 }
 @Test void securityVersionChangeStopsBackgroundAccessUntilFreshUserInteraction()throws Exception{
  var user=login("20260001");long s=session(user);var goal=watchingGoal(user,s);long id=businessId(goal),before=notices(id);
  jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE username='20260001'").update();
  try{due(goal);monitor.check();var fresh=login("20260001");goal=ok(fresh.get(path(s)+"/"+goal.path("id").asLong()));assertThat(goal.path("stage").asText()).isEqualTo("PAUSED");assertThat(goal.path("tasks").get(0).path("watching").asBoolean()).isFalse();assertThat(notices(id)).isEqualTo(before);}
  finally{jdbc.sql("UPDATE app_user SET security_version=security_version-1 WHERE username='20260001'").update();}
 }
 @Test void deletingConversationRemovesWatchAndKeepsSubmittedBusiness()throws Exception{
  var user=login("20260001");long s=session(user);var goal=watchingGoal(user,s);long id=businessId(goal),before=notices(id);
  assertThat(user.delete("/api/assistant/sessions/"+s).statusCode()).isEqualTo(204);decide(id,"RETURN");monitor.check();assertThat(notices(id)).isEqualTo(before);
  assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_request WHERE id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);
 }
 JsonNode watchingGoal(Browser user,long s)throws Exception{
  desiredTarget="TRACK";var goal=savedGoal(user,s);decisions.add(call("submit_task",Map.of("taskKey","certificate")));decisions.add(call("verify_submission",Map.of("taskKey","certificate")));decisions.add(call("watch_task",Map.of("taskKey","certificate")));
  goal=act(user,s,goal,authorize(goal));assertThat(goal.path("stage").asText()).isEqualTo("MONITORING");return goal;
 }
 long businessId(JsonNode goal){return goal.path("tasks").get(0).path("mission").path("business").path("id").asLong();}
 long notices(long id){return jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE source_type='CERTIFICATE' AND source_id=:id AND title='Agent 跟踪状态更新'").param("id",id).query(Long.class).single();}
 void due(JsonNode goal){jdbc.sql("UPDATE agent_goal SET monitor_after=:due WHERE id=:id").param("due",AgentMissionTools.now().minusMinutes(1)).param("id",goal.path("id").asLong()).update();}
 void decide(long id,String decision)throws Exception{var reviewer=login("affairs1001");var original=ok(reviewer.get("/api/certificates/review/"+id));ok(reviewer.post("/api/certificates/review/"+id+"/decision",Map.of("version",original.path("version").asInt(),"action",decision,"comment","隔离验收实际审核结果")));}
 JsonNode savedGoal(Browser user,long s)throws Exception{
  prepareCertificate();var goal=ok(user.post(path(s),input()));saveChild(user,s,goal.path("tasks").get(0).path("mission"));
  decisions.add(call("refresh_task",Map.of("taskKey","certificate")));decisions.add(call("await_user",Map.of("taskKey","certificate","question","请核对原申请后确认提交。")));
  return act(user,s,goal,action(goal,"CONTINUE"));
 }
 Map<String,Object> authorize(JsonNode goal){var body=action(goal,"AUTHORIZE_SUBMIT");body.put("taskKey","certificate");body.put("confirmed",true);body.put("proposalHash",goal.path("tasks").get(0).path("submissionPreview").path("proposalHash").asText());return body;}
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
