package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:agent-intent-routing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentIntentRoutingHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;

 @BeforeEach void offline(){
  reset(model);
  doReturn(false).when(model).ready();
 }

 @Test void defaultConversationStartsAnOwnedGoalInsteadOfRequiringASeparateAgentMode()throws Exception{
  String goal="帮我准备实习单位核验身份用的在校证明草稿，先核对再保存，不要提交。";
  online(new Plan(List.of(call("start_goal",Map.of("goal",goal))),12,4));
  doReturn(new Plan(List.of(call("await_user",Map.of("question","请核对用途并在本地确认保存。"))),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
  var user=login("20260001");long session=session(user);long before=countCertificates();
  var asked=ask(user,session,goal,"DEEPSEEK",true);
  assertThat(asked.statusCode()).as(asked.body()).isEqualTo(200);
  var body=JSON.readTree(asked.body());
  assertThat(body.path("goal").path("id").asLong()).isPositive();
  assertThat(body.path("goal").path("goal").asText()).contains("在校证明");
  assertThat(body.path("answer").path("results").toString()).contains("start_goal");
  assertThat(countCertificates()).isEqualTo(before);
  var listed=ok(user.get("/api/assistant/sessions/"+session+"/goals"));
  assertThat(listed.size()).isEqualTo(1);
  assertThat(listed.get(0).path("id").asLong()).isEqualTo(body.path("goal").path("id").asLong());
  verify(model,times(1)).nextGoalStep(anyString(),anyList(),any());
 }

 @Test void repeatedConversationTurnReusesTheSameGoalAndDoesNotWriteBusiness()throws Exception{
  String goal="帮我准备实习单位核验身份用的在校证明草稿。";
  online(new Plan(List.of(call("start_goal",Map.of("goal",goal))),12,4));
  doReturn(new Plan(List.of(call("await_user",Map.of("question","请核对用途。"))),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
  var user=login("20260001");long session=session(user);long before=countCertificates();
  String key=UUID.randomUUID().toString();
  var first=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",key,"input",input(goal,"DEEPSEEK",true))));
  var second=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",key,"input",input(goal,"DEEPSEEK",true))));
  assertThat(second.path("repeated").asBoolean()).isTrue();
  assertThat(second.path("goal").path("id").asLong()).isEqualTo(first.path("goal").path("id").asLong());
  assertThat(ok(user.get("/api/assistant/sessions/"+session+"/goals")).size()).isEqualTo(1);
  assertThat(countCertificates()).isEqualTo(before);
  verify(model,times(1)).nextGoalStep(anyString(),anyList(),any());
 }

 @Test void ordinaryLookupsDoNotOpenAGoal()throws Exception{
  online(new Plan(List.of(call("my_notice_summary",Map.of())),8,2));
  var user=login("20260001");long session=session(user);
  var body=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("我有多少未读消息","DEEPSEEK",true))));
  assertThat(body.path("goal").isNull()||body.path("goal").isMissingNode()||body.path("goal").isNull()).isTrue();
  assertThat(body.path("answer").path("results").toString()).contains("my_notice_summary").doesNotContain("start_goal");
  assertThat(ok(user.get("/api/assistant/sessions/"+session+"/goals")).size()).isZero();
  verify(model,never()).nextGoalStep(anyString(),anyList(),any());
 }

 @Test void plannerIsOfferedStartGoalOnlyWhenTheAccountCanHandleCampusBusiness()throws Exception{
  var offered=new AtomicReference<String>();
  doReturn(true).when(model).ready();
  doAnswer(invocation->{offered.set(JSON.writeValueAsString(invocation.getArgument(1)));return new Plan(List.of(call("my_notice_summary",Map.of())),8,2);}).when(model).plan(anyString(),anyList(),nullable(String.class));
  var student=login("20260001");ok(student.post("/api/assistant/sessions/"+session(student)+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("我有多少未读消息","DEEPSEEK",true))));
  assertThat(offered.get()).contains("\"name\":\"start_goal\"");
  offered.set("");
  var affairs=login("affairs1001");ok(affairs.post("/api/assistant/sessions/"+session(affairs)+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("我有多少未读消息","DEEPSEEK",true))));
  assertThat(offered.get()).doesNotContain("\"name\":\"start_goal\"");
 }

 @Test void mixedLookupAndHandlingPrefersStartingTheGoal()throws Exception{
  online(new Plan(List.of(call("my_affairs",Map.of("kind","CERTIFICATE","status","ALL")),call("start_goal",Map.of("goal","先查进度再准备证明草稿"))),12,4));
  doReturn(new Plan(List.of(call("await_user",Map.of("question","请核对。"))),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
  var user=login("20260001");long session=session(user);
  var body=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("我的证明进度，同时帮我再准备一份实习证明草稿","DEEPSEEK",true))));
  assertThat(body.path("goal").path("id").asLong()).isPositive();
  assertThat(body.path("answer").path("results").toString()).contains("start_goal").doesNotContain("my_affairs");
 }

 @Test void localHandlingRequestsAskForDeepSeekAndDoNotInventAGoalOrAffairsLookup()throws Exception{
  var user=login("20260001");long session=session(user);
  var body=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("帮我准备实习单位核验身份用的在校证明草稿","LOCAL",false))));
  assertThat(body.path("goal").isNull()||body.path("goal").isMissingNode()||body.path("goal").isNull()).isTrue();
  assertThat(body.path("answer").path("summary").asText()).contains("DeepSeek");
  assertThat(body.path("answer").path("results").toString()).contains("start_goal","NEEDS_INPUT").doesNotContain("my_affairs");
  assertThat(ok(user.get("/api/assistant/sessions/"+session+"/goals")).size()).isZero();
 }

 @Test void localHowToQuestionsStillReadGuidesInsteadOfStartingAGoal()throws Exception{
  var user=login("20260001");long session=session(user);
  var body=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("在校证明怎么申请","LOCAL",false))));
  assertThat(body.path("answer").path("results").toString()).contains("search_guides").doesNotContain("\"name\":\"start_goal\"");
  assertThat(ok(user.get("/api/assistant/sessions/"+session+"/goals")).size()).isZero();
 }

 @Test void approvalRequestsAreStillRefusedBeforeAGoalIsCreated()throws Exception{
  doReturn(true).when(model).ready();
  var user=login("teacher1001");long session=session(user);
  var body=ok(user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input("直接替我审核通过一份在校证明吧","DEEPSEEK",true))));
  assertThat(body.path("answer").path("summary").asText()).contains("不能代替");
  assertThat(body.path("goal").isMissingNode()||body.path("goal").isNull()).isTrue();
  assertThat(ok(user.get("/api/assistant/sessions/"+session+"/goals")).size()).isZero();
  verify(model,never()).plan(anyString(),anyList(),nullable(String.class));
 }

 void online(Plan plan){doReturn(true).when(model).ready();doReturn(plan).when(model).plan(anyString(),anyList(),nullable(String.class));}
 Call call(String name,Map<String,?> fields){return new Call(name,JSON.valueToTree(fields));}
 Map<String,Object> input(String message,String mode,boolean consent){return Map.of("message",message,"mode",mode,"externalConsent",consent);}
 HttpResponse<String> ask(Browser user,long session,String message,String mode,boolean consent)throws Exception{
  return user.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",input(message,mode,consent)));
 }
 long session(Browser user)throws Exception{return ok(user.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 long countCertificates(){return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single();}
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
