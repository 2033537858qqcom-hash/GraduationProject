package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:agent-goal-autopilot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalAutopilotHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 final Deque<Call> decisions=new ArrayDeque<>();
 @BeforeEach void planning(){
  decisions.clear();
  doReturn(true).when(model).ready();
  doAnswer(invocation->new Plan(List.of(decisions.isEmpty()?call("await_user",Map.of("question","请补充缺少的实际条件。")):decisions.removeFirst()),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
 }
 Call call(String name,Map<String,?> fields){return new Call(name,JSON.valueToTree(fields));}
 Map<String,Object> task(String key,String kind,String target){return Map.of("key",key,"kind",kind,"label","办理本人业务","target",target,"dependsOn",List.of());}

 @Test void aFullyStatedCertificateGoalSavesAndSubmitsWithoutAnotherConfirmation()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE","TRACK")))));
  decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","实习单位核验在校身份"))));
  decisions.add(call("prepare_task",Map.of("taskKey","certificate")));
  decisions.add(call("submit_task",Map.of("taskKey","certificate")));
  decisions.add(call("verify_submission",Map.of("taskKey","certificate")));
  decisions.add(call("watch_task",Map.of("taskKey","certificate")));
  var user=login("20260001");long before=count();var goal=ok(user.post(path(session(user)),Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我办在校证明，用途是实习单位核验在校身份，直接提交并跟踪。","externalConsent",true)));
  assertThat(count()).isEqualTo(before+1);
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("SUBMITTED");
  assertThat(goal.path("tasks").get(0).path("submissionVerified").asBoolean()).isTrue();
  assertThat(goal.path("stage").asText()).isIn("MONITORING","DONE");
 }

 @Test void aDraftOnlyCertificateGoalSavesButDoesNotSubmit()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE","DRAFT")))));
  decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","实习单位核验在校身份"))));
  decisions.add(call("prepare_task",Map.of("taskKey","certificate")));
  decisions.add(call("finish",Map.of()));
  var user=login("20260001");long before=count();var goal=ok(user.post(path(session(user)),Map.of("requestKey",UUID.randomUUID().toString(),"goal","只准备在校证明草稿，用途是实习单位核验在校身份，不要提交。","externalConsent",true)));
  assertThat(count()).isEqualTo(before+1);
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("DRAFT");
  assertThat(goal.path("stage").asText()).isEqualTo("DONE");
 }

 @Test void missingPurposeStillWritesNothing()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE","SUBMIT")))));
  decisions.add(call("prepare_task",Map.of("taskKey","certificate")));
  var user=login("20260001");long before=count();var goal=ok(user.post(path(session(user)),Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我办一份在校证明，用途等我再说。","externalConsent",true)));
  assertThat(count()).isEqualTo(before);
  assertThat(goal.path("tasks").get(0).path("mission").path("business").isNull()).isTrue();
  assertThat(goal.path("stage").asText()).isNotEqualTo("DONE");
 }

 String path(long session){return "/api/assistant/sessions/"+session+"/goals";}
 long session(Browser browser)throws Exception{return ok(browser.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 long count(){return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single();}
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
