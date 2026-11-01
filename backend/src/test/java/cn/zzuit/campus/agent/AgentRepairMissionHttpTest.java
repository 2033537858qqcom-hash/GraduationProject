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
 "spring.datasource.url=jdbc:h2:mem:repair-mission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentRepairMissionHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 long location;
 @BeforeAll void fixture(){
  jdbc.sql("INSERT INTO repair_location(campus_id,code,name,kind,detail_hint,provenance,enabled) VALUES(1,'MISSION-REPAIR','隔离报修地点','CAMPUS_PUBLIC','示例地点','DEMO',TRUE)").update();
  location=jdbc.sql("SELECT id FROM repair_location WHERE code='MISSION-REPAIR'").query(Long.class).single();
 }
 @BeforeEach void offline(){doReturn(false).when(model).ready();}
 Map<String,Object> repair(){return Map.of("category","ELECTRICITY","title","照明故障测试","description","私有故障描述：灯具无法开启","locationId",location,"locationDetail","走廊东侧","contact","private@example.test","contactWindow","白天联系");}
 long session(Browser user)throws Exception{return ok(user.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 String path(long s){return "/api/assistant/sessions/"+s+"/missions";}
 JsonNode create(Browser user,long s,String goal,String mode,Map<String,Object> fields)throws Exception {var input=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"goal",goal,"mode",mode,"externalConsent",mode.equals("DEEPSEEK")));if(fields!=null)input.put("repairFields",fields);return ok(user.post(path(s),input));}
 Map<String,Object> command(JsonNode task,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",task.path("version").asInt(),"action",action));}
 JsonNode act(Browser user,long s,JsonNode task,Map<String,Object> body)throws Exception{return ok(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",body));}
 @Test void teacherWithoutPortalCanCompleteConfirmedRepairAndVerifyOriginalDraft()throws Exception {
  var user=login("teacher1001");long s=session(user);long before=jdbc.sql("SELECT COUNT(*) FROM repair_request").query(Long.class).single();
  var task=create(user,s,"帮我准备报修草稿","LOCAL",repair());assertThat(task.path("kind").asText()).isEqualTo("REPAIR");assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
  assertThat(task.path("steps").toString()).contains("check_repair_requirements","inspect_repair_location","prepare_repair");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM repair_request").query(Long.class).single()).isEqualTo(before);
  var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,s,task,confirm);
  assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("targetPath").asText()).startsWith("/repairs/mine?id=");
  assertThat(task.path("steps").toString()).contains("save_repair","verify_repair");
  var original=ok(user.get("/api/repairs/mine/"+task.path("business").path("id").asLong()));assertThat(original.path("status").asText()).isEqualTo("DRAFT");assertThat(original.path("contact").asText()).isEqualTo("private@example.test");
  assertThat(act(user,s,task,confirm).path("business").path("id")).isEqualTo(task.path("business").path("id"));
 }
 @Test void missingInputsCanBeRevisedAndMixedGoalRequiresChoice()throws Exception {
  var user=login("20260001");long s=session(user);var task=create(user,s,"我要报修","LOCAL",null);
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("missing").toString()).contains("category","locationId","contact");
  assertThat(task.path("repairLocations").size()).isGreaterThan(0);
  var revise=command(task,"REVISE");revise.put("repairFields",repair());task=act(user,s,task,revise);assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
  var ambiguous=Map.of("requestKey",UUID.randomUUID().toString(),"goal","我要报修和预约教室","mode","LOCAL","externalConsent",false);
  assertThat(user.post(path(s),ambiguous).statusCode()).isEqualTo(400);
 }
 @Test void unavailableLocationInvalidatesConfirmationAndCannotWrite()throws Exception {
  var user=login("20260001");long s=session(user);var task=create(user,s,"准备报修草稿","LOCAL",repair());
  jdbc.sql("UPDATE repair_location SET enabled=FALSE,version=version+1 WHERE id=:id").param("id",location).update();
  try {var confirm=command(task,"CONFIRM");confirm.put("confirmed",true);confirm.put("proposalHash",task.path("proposalHash").asText());task=act(user,s,task,confirm);assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("business").isNull()).isTrue();}
  finally{jdbc.sql("UPDATE repair_location SET enabled=TRUE,version=version+1 WHERE id=:id").param("id",location).update();}
 }
 @Test void modelOnlySeesSanitizedObservationsAndRepairTools()throws Exception {
  doReturn(true).when(model).ready();doAnswer(invocation->{List<String> allowed=invocation.getArgument(1);String observations=JSON.writeValueAsString(invocation.getArgument(2));assertThat(observations).doesNotContain("private@example.test","私有故障描述","隔离报修地点");assertThat(allowed).doesNotContain("find_venues","save_reservation");return new Plan(List.of(new Call(allowed.get(0),JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());
  var user=login("teacher1001");var task=create(user,session(user),"准备报修草稿","DEEPSEEK",repair());assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");verify(model,atLeast(3)).nextMissionStep(anyString(),anyList(),anyList());
 }
 @Test void unrelatedRoleAndAnotherUsersTaskAreDenied()throws Exception {
  var admin=login("admin");long own=session(admin);assertThat(admin.post(path(own),Map.of("requestKey",UUID.randomUUID().toString(),"goal","准备报修草稿","mode","LOCAL","externalConsent",false,"repairFields",repair())).statusCode()).isEqualTo(403);
  var user=login("teacher1001");long s=session(user);var task=create(user,s,"准备报修草稿","LOCAL",repair());assertThat(login("20260002").get(path(s)+"/"+task.path("id").asLong()).statusCode()).isEqualTo(404);
 }
 @Test void explicitKindDisambiguatesGoalAndCannotBeChangedDuringRevision()throws Exception {
  var user=login("teacher1001");long s=session(user);var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","教室需要报修，也要预约","kind","REPAIR","mode","LOCAL","externalConsent",false,"repairFields",repair()));
  var task=ok(user.post(path(s),body));assertThat(task.path("kind").asText()).isEqualTo("REPAIR");
  var revise=command(task,"REVISE");revise.put("fields",Map.of("campus","EAST"));assertThat(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",revise).statusCode()).isEqualTo(400);
  assertThat(ok(user.get(path(s))).get(0).path("kind").asText()).isEqualTo("REPAIR");
 }
 @Test void invalidContactsAndWithdrawnGuidesBlockConfirmation()throws Exception {
  var user=login("teacher1001");long s=session(user);var fields=new HashMap<String,Object>(repair());fields.put("contact","invalid-contact");var task=create(user,s,"准备报修草稿","LOCAL",fields);
  assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("repairPreview").path("issues").size()).isGreaterThan(0);
  jdbc.sql("UPDATE agent_guide SET status='WITHDRAWN' WHERE slug='repair'").update();
  try {var revise=command(task,"REVISE");revise.put("repairFields",repair());task=act(user,s,task,revise);assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("repairPreview").path("issues").toString()).contains("指南");}
  finally {jdbc.sql("UPDATE agent_guide SET status='PUBLISHED' WHERE slug='repair'").update();}
 }
 @Test void unrecognizedOrWrongDomainModelActionsCannotSaveRepair()throws Exception {
  doReturn(true).when(model).ready();doReturn(new Plan(List.of(new Call("save_reservation",JSON.valueToTree(Map.of()))),10,2)).when(model).nextMissionStep(anyString(),anyList(),anyList());
  var user=login("teacher1001");long before=jdbc.sql("SELECT COUNT(*) FROM repair_request").query(Long.class).single();var task=create(user,session(user),"准备报修草稿","DEEPSEEK",repair());
  assertThat(task.path("stage").asText()).isEqualTo("FAILED");assertThat(task.path("steps").toString()).contains("MODEL_ACTION_REJECTED");assertThat(jdbc.sql("SELECT COUNT(*) FROM repair_request").query(Long.class).single()).isEqualTo(before);
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
