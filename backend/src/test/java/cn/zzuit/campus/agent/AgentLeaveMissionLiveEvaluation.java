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
 "spring.datasource.url=jdbc:h2:mem:leave-mission-live;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentLeaveMissionLiveEvaluation {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@Autowired DeepSeekClient model;

 Map<String,Object> leave(){String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(5).toString();return Map.of("reasonType","PERSONAL","reason","仅本地保存的实际请假事由","contact","private@example.test","startDate",day,"startTime","09:00","endDate",day,"endTime","17:00");}
 long session(Browser user)throws Exception{return ok(user.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 String path(long s){return "/api/assistant/sessions/"+s+"/missions";}
 Map<String,Object> input(Map<String,Object> fields){var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备请假草稿","mode","LOCAL","externalConsent",false));if(fields!=null)body.put("leaveFields",fields);return body;}
 Map<String,Object> command(JsonNode task,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",task.path("version").asInt(),"action",action));}
 JsonNode act(Browser user,long s,JsonNode task,Map<String,Object> body)throws Exception{return ok(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",body));}
 @Test void realModelCompletesSyntheticConfirmedLeaveDraft()throws Exception {
  if(!"true".equals(System.getenv("CAMPUS_AGENT_LIVE_EVAL")))throw new IllegalStateException("Explicit live evaluation opt-in required");assertThat(model.ready()).isTrue();
  var user=login("20260001");long s=session(user);JsonNode task=null;
  try{
   var body=input(leave());body.put("mode","DEEPSEEK");body.put("externalConsent",true);task=ok(user.post(path(s),body));assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
   var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,s,task,confirm);assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("status").asText()).isEqualTo("DRAFT");assertThat(task.path("steps").size()).isEqualTo(6);
  }finally{if(task!=null){var file=java.nio.file.Path.of("../output/agent-leave-missions-20261101/live-goal.json");java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,JSON.writeValueAsString(Map.of("scope","synthetic-in-memory-leave-draft","kind",task.path("kind"),"stage",task.path("stage"),"steps",task.path("steps"),"business",task.path("business"))));}}
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
