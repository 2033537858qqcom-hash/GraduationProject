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
 "spring.datasource.url=jdbc:h2:mem:agent-mission-live;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentMissionLiveEvaluation {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@Autowired DeepSeekClient model;
 @Autowired AgentMissionTools missionTools;
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

 Map<String,Object> fields(){var day=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4);return Map.of("campus","EAST","startsAt",day+"T09:00","endsAt",day+"T11:00","participants",30,"keyword","任务测试教室","activityTheme","Agent隔离测试活动","activityType","ACADEMIC","contactPhone","13800138000");}
 long session(Browser b)throws Exception{return ok(b.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 String path(long session){return "/api/assistant/sessions/"+session+"/missions";}
 JsonNode start(Browser b,long session,String mode)throws Exception{return ok(b.post(path(session),Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备一个场地预约草稿","mode",mode,"externalConsent",mode.equals("DEEPSEEK"),"fields",fields())));}
 Map<String,Object> command(JsonNode view,String action){var body=new HashMap<String,Object>();body.put("requestKey",UUID.randomUUID().toString());body.put("version",view.path("version").asInt());body.put("action",action);return body;}
 JsonNode act(Browser b,long session,JsonNode view,Map<String,Object> command)throws Exception{return ok(b.post(path(session)+"/"+view.path("id").asLong()+"/actions",command));}
 @Test void oneSyntheticGoalRunsRealModelObservationLoopAndVerifiesDraft()throws Exception {
  if(!"true".equals(System.getenv("CAMPUS_AGENT_LIVE_EVAL")))throw new IllegalStateException("Explicit live evaluation opt-in required");
  assertThat(model.ready()).isTrue();var user=login(username);long session=session(user);
  JsonNode task=null;
  try {
   task=start(user,session,"DEEPSEEK");assertThat(task.path("stage").asText()).isEqualTo("WAITING_SELECTION");
   var select=command(task,"SELECT");select.put("optionKey",task.path("options").get(0).path("key").asText());task=act(user,session,task,select);
   assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
   var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,session,task,confirm);
   assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("status").asText()).isEqualTo("DRAFT");
   assertThat(task.path("steps").size()).isGreaterThanOrEqualTo(7);
  } finally {
   if(task!=null){var out=java.nio.file.Path.of("../output/agent-runtime-20261101/live-goal.json");java.nio.file.Files.createDirectories(out.getParent());java.nio.file.Files.writeString(out,JSON.writeValueAsString(Map.of("scope","synthetic-in-memory-reservation-draft","stage",task.path("stage"),"steps",task.path("steps"),"business",task.path("business"))));}
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
