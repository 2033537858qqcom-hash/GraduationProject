package cn.zzuit.campus.agent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;import java.net.http.*;import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.json.JsonMapper;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:leave-mission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentLeaveMissionHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 @BeforeEach void offline(){doReturn(false).when(model).ready();}
 Map<String,Object> leave(){String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(5).toString();return Map.of("reasonType","PERSONAL","reason","仅本地保存的实际请假事由","contact","private@example.test","startDate",day,"startTime","09:00","endDate",day,"endTime","17:00");}
 long session(Browser user)throws Exception{return ok(user.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 String path(long s){return "/api/assistant/sessions/"+s+"/missions";}
 Map<String,Object> input(Map<String,Object> fields){var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备请假草稿","mode","LOCAL","externalConsent",false));if(fields!=null)body.put("leaveFields",fields);return body;}
 Map<String,Object> command(JsonNode task,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",task.path("version").asInt(),"action",action));}
 JsonNode act(Browser user,long s,JsonNode task,Map<String,Object> body)throws Exception{return ok(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",body));}
 @Test void confirmedLeaveTaskSavesAndVerifiesOwnedDraftWithoutSubmitting()throws Exception{
  var user=login("20260001");long s=session(user);long before=jdbc.sql("SELECT COUNT(*) FROM leave_request").query(Long.class).single();var task=ok(user.post(path(s),input(leave())));
  assertThat(task.path("kind").asText()).isEqualTo("LEAVE");assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");assertThat(task.path("steps").toString()).contains("check_leave_requirements","inspect_leave_eligibility","prepare_leave");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM leave_request").query(Long.class).single()).isEqualTo(before);
  var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,s,task,confirm);assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("status").asText()).isEqualTo("DRAFT");assertThat(task.path("steps").toString()).contains("save_leave","verify_leave");
  long id=task.path("business").path("id").asLong();assertThat(task.path("business").path("targetPath").asText()).isEqualTo("/portal/leave?id="+id+"&tab=details");
  assertThat(act(user,s,task,confirm).path("business").path("id").asLong()).isEqualTo(id);
 }
 @Test void missingInputsAreRequestedAndCanBeRevised()throws Exception{
  var user=login("20260002");long s=session(user);var task=ok(user.post(path(s),input(null)));assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("missing").toString()).contains("reasonType","contact","startTime");var revise=command(task,"REVISE");revise.put("leaveFields",leave());task=act(user,s,task,revise);assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
 }
 @Test void reversedTimeAndOverlongDurationCannotReachConfirmation()throws Exception{
  var user=login("20260001");long s=session(user);var f=new HashMap<String,Object>(leave());f.put("endTime","08:00");assertThat(ok(user.post(path(s),input(f))).path("stage").asText()).isEqualTo("WAITING_INPUT");f.put("endDate",java.time.LocalDate.parse(f.get("startDate").toString()).plusDays(35).toString());assertThat(ok(user.post(path(s),input(f))).path("leavePreview").path("issues").toString()).contains("30");
 }
 @Test void changedGuideRequiresFreshConfirmation()throws Exception{
  var user=login("20260001");long s=session(user);var task=ok(user.post(path(s),input(leave())));jdbc.sql("UPDATE agent_guide SET version=version+1 WHERE slug='leave'").update();
  try{var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,s,task,confirm);assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("business").isNull()).isTrue();}finally{jdbc.sql("UPDATE agent_guide SET version=version-1 WHERE slug='leave'").update();}
 }
 @Test void wrongRoleForeignSessionAndMixedGoalAreRejected()throws Exception{
  var teacher=login("teacher1001");long ts=session(teacher);assertThat(teacher.post(path(ts),input(leave())).statusCode()).isEqualTo(403);var user=login("20260001");long s=session(user);var task=ok(user.post(path(s),input(leave())));assertThat(login("20260002").get(path(s)+"/"+task.path("id").asLong()).statusCode()).isEqualTo(404);var mixed=input(null);mixed.put("goal","请假后还要预约教室");assertThat(user.post(path(s),mixed).statusCode()).isEqualTo(400);
 }
 @Test void modelReceivesNoLeaveReasonContactOrIdentityInObservations()throws Exception{
  doReturn(true).when(model).ready();doAnswer(invocation->{List<String> allowed=invocation.getArgument(1);String obs=JSON.writeValueAsString(invocation.getArgument(2));assertThat(obs).doesNotContain("private@example.test","实际请假事由","studentId","classGroupId");assertThat(allowed).doesNotContain("save_repair","find_venues");return new Plan(List.of(new Call(allowed.get(0),JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());var user=login("20260001");var body=input(leave());body.put("mode","DEEPSEEK");body.put("externalConsent",true);var task=ok(user.post(path(session(user)),body));assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
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
