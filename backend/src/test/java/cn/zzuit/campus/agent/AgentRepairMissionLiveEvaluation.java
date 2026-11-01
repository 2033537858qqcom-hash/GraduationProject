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
 "spring.datasource.url=jdbc:h2:mem:repair-mission-live;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentRepairMissionLiveEvaluation {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@Autowired DeepSeekClient model;
 long location;
 @BeforeAll void fixture(){
  jdbc.sql("INSERT INTO repair_location(campus_id,code,name,kind,detail_hint,provenance,enabled) VALUES(1,'MISSION-REPAIR','隔离报修地点','CAMPUS_PUBLIC','示例地点','DEMO',TRUE)").update();
  location=jdbc.sql("SELECT id FROM repair_location WHERE code='MISSION-REPAIR'").query(Long.class).single();
 }

 Map<String,Object> repair(){return Map.of("category","ELECTRICITY","title","照明故障测试","description","私有故障描述：灯具无法开启","locationId",location,"locationDetail","走廊东侧","contact","private@example.test","contactWindow","白天联系");}
 long session(Browser user)throws Exception{return ok(user.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 String path(long s){return "/api/assistant/sessions/"+s+"/missions";}
 JsonNode create(Browser user,long s,String goal,String mode,Map<String,Object> fields)throws Exception {var input=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"goal",goal,"mode",mode,"externalConsent",mode.equals("DEEPSEEK")));if(fields!=null)input.put("repairFields",fields);return ok(user.post(path(s),input));}
 Map<String,Object> command(JsonNode task,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",task.path("version").asInt(),"action",action));}
 JsonNode act(Browser user,long s,JsonNode task,Map<String,Object> body)throws Exception{return ok(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",body));}
 @Test void realModelCompletesSyntheticRepairGoalAfterConfirmation()throws Exception {
  if(!"true".equals(System.getenv("CAMPUS_AGENT_LIVE_EVAL")))throw new IllegalStateException("Explicit live evaluation opt-in required");
  assertThat(model.ready()).isTrue();var user=login("teacher1001");long s=session(user);JsonNode task=null;
  try {
   task=create(user,s,"准备报修草稿","DEEPSEEK",repair());assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
   var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,s,task,confirm);
   assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("status").asText()).isEqualTo("DRAFT");assertThat(task.path("steps").size()).isEqualTo(6);
  }finally{
   if(task!=null){var path=java.nio.file.Path.of("../output/agent-repair-missions-20261101/live-goal.json");java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.writeString(path,JSON.writeValueAsString(Map.of("scope","synthetic-in-memory-repair-draft","kind",task.path("kind"),"stage",task.path("stage"),"steps",task.path("steps"),"business",task.path("business"))));}
  }
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
