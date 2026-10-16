package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.DeepSeekClient;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:campus-agent-housing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.repair.reminders.enabled=false"})
class AgentHousingIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 @MockitoSpyBean DeepSeekClient model;
 final JsonMapper json=JsonMapper.builder().build();
 static final String ROOT="/api/admin/housing";
 @BeforeEach void offline(){doReturn(false).when(model).ready();}
 @Test void housingCapabilitiesAndPreparationRequireStudentPermissionAndCsrf() throws Exception {
  assertThat(new Browser().get("/api/assistant/capabilities").statusCode()).isEqualTo(401);
  var s=login("20260001");assertThat(ok(s.get("/api/assistant/capabilities")).toString()).contains("my_housing","my_housing_transfers","housing_transfer_status");
  for(String name:List.of("teacher1001","dorm1001","admin","affairs1001")) {
   var b=login(name);var caps=b.get("/api/assistant/capabilities");if(caps.statusCode()==200)assertThat(ok(caps).toString()).doesNotContain("my_housing");else assertThat(caps.statusCode()).isEqualTo(403);
   assertThat(b.send("POST","/api/assistant/housing/prepare",input(fields())).statusCode()).isEqualTo(403);
  }
  assertThat(s.http.send(s.builder("/api/assistant/housing/prepare").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input(fields())))).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
 }
 @Test void currentRoomQueryIsLocalAndOnlyDescribesCaller() throws Exception {
  var f=resident();var answer=ok(query(f.browser(),"我的住宿",null));
  assertThat(answer.toString()).contains("my_housing","演示宿舍楼","/housing/mine").doesNotContain("bedId","studentId","identityNumber","1 号床");
  var stranger=login(student());assertThat(ok(query(stranger,"我的住宿",null)).toString()).contains("NO_DATA").doesNotContain("演示宿舍楼");
  var refused=ok(query(f.browser(),"我要查询其他学生的宿舍和床位",null));assertThat(refused.path("results").isEmpty()).isTrue();
 }
 @Test void guideAndMissingStayNeverCreateBusinessRecords() throws Exception {
  var b=login(student());long count=count();var preview=ok(b.send("POST","/api/assistant/housing/prepare",input(fields())));
  assertThat(preview.path("ready").asBoolean()).isFalse();assertThat(preview.toString()).contains("暂无当前入住记录");
  assertThat(ok(query(b,"调宿怎么办理",null)).toString()).contains("住宿与调宿办理指南","不能选择具体床位");assertThat(count()).isEqualTo(count);
 }
 @Test void confirmedSaveCreatesOneDraftAndOriginalPageMustSubmitIt() throws Exception {
  var f=resident();var task=prepared(f);long before=count();String path=path(task);
  assertThat(f.browser().send("POST",path+"/save",confirm(task,false)).statusCode()).isEqualTo(400);
  var saved=ok(f.browser().send("POST",path+"/save",confirm(task,true)));long id=saved.path("business").path("id").asLong();
  assertThat(saved.path("business").path("status").asText()).isEqualTo("DRAFT");assertThat(count()).isEqualTo(before+1);
  assertThat(ok(f.browser().send("POST",path+"/save",confirm(task,true))).path("business").path("id").asLong()).isEqualTo(id);
  var row=ok(f.browser().get("/api/housing/transfers/mine")).path(0);assertThat(row.path("allowedActions").toString()).contains("edit","submit");
  ok(f.browser().send("POST","/api/housing/transfers/mine/"+id+"/submit",Map.of("requestKey",key(),"version",0,"comment","本人核对后提交")));
  assertThat(ok(f.browser().get(path)).path("business").path("status").asText()).isEqualTo("SUBMITTED");
  assertThat(count()).isEqualTo(before+1);
 }
 @Test void progressAndPageContextCannotExposeAnotherStudentsReasonsOrMaterial() throws Exception {
  var f=resident();var task=prepared(f);long id=ok(f.browser().send("POST",path(task)+"/save",confirm(task,true))).path("business").path("id").asLong();
  var answer=ok(query(f.browser(),"我的调宿进度",null));assertThat(answer.toString()).contains("HOUSING:"+id,"/housing/transfer?id="+id).doesNotContain("私有调宿原因","私有材料说明");
  var context=Map.<String,Object>of("type","HOUSING_MINE","id",id);
  assertThat(ok(query(f.browser(),"当前调宿的处理进度",context)).toString()).contains("housing_transfer_status");
  assertThat(query(login(student()),"当前调宿的处理进度",context).statusCode()).isEqualTo(404);
  var stranger=login(student());assertThat(stranger.get(path(task)).statusCode()).isEqualTo(404);
  assertThat(ok(query(stranger,"我的调宿进度",null)).toString()).doesNotContain("HOUSING:"+id);
 }
 @Test void checkoutAndReentryRejectThePreviouslyReviewedStayToken() throws Exception {
  var f=resident();var task=prepared(f);checkout(f.student());checkInStudent(f.student(),f.place().bed());long before=count();
  assertThat(f.browser().send("POST",path(task)+"/save",confirm(task,true)).statusCode()).isEqualTo(409);assertThat(count()).isEqualTo(before);
  var refreshed=ok(f.browser().get(path(task)));assertThat(refreshed.path("housingPreview").path("stayToken").asText()).isNotEqualTo(task.path("housingPreview").path("stayToken").asText());
  assertThat(ok(f.browser().send("POST",path(task)+"/save",confirm(refreshed,true))).path("business").path("status").asText()).isEqualTo("DRAFT");
 }
 @Test void anExistingApplicationAppearingAfterPreviewPreventsAnotherDraft() throws Exception {
  var f=resident();var task=prepared(f);var body=new HashMap<String,Object>(fields());body.put("requestKey",key());body.put("version",0);
  ok(f.browser().send("POST","/api/housing/transfers/mine",body));long before=count();
  assertThat(f.browser().send("POST",path(task)+"/save",confirm(task,true)).statusCode()).isEqualTo(409);assertThat(count()).isEqualTo(before);
 }
 @Test void modelMayOnlyPatchExplicitTextAndNeedsPerRequestConsent() throws Exception {
  var f=resident();doReturn(true).when(model).ready();doReturn(new Plan(List.of(new Call("prepare_housing_fields",json.valueToTree(Map.of("reason","本次明确原因")))),4,2)).when(model).prepareHousing(anyString());
  var data=new HashMap<String,Object>(input(fields()));data.put("mode","DEEPSEEK");data.put("externalConsent",false);data.put("message","本次明确原因");
  assertThat(f.browser().send("POST","/api/assistant/housing/prepare",data).statusCode()).isEqualTo(400);verify(model,never()).prepareHousing(anyString());
  data.put("externalConsent",true);var response=ok(f.browser().send("POST","/api/assistant/housing/prepare",data));
  assertThat(response.path("fields").path("reason").asText()).isEqualTo("本次明确原因");assertThat(response.path("fields").path("materialNote").asText()).isEqualTo("私有材料说明");verify(model).prepareHousing("本次明确原因");
 }
 @Test void forgedTargetBedOrUnknownModelToolDoesNotPersistAnyPreviewFields() throws Exception {
  var f=resident();var task=prepared(f);doReturn(true).when(model).ready();
  for(var plan:List.of(new Plan(List.of(new Call("prepare_housing_fields",json.valueToTree(Map.of("targetBedId",99)))),1,1),new Plan(List.of(new Call("submit_housing",json.valueToTree(Map.of()))),1,1))) {
   doReturn(plan).when(model).prepareHousing(anyString());var data=new HashMap<String,Object>(input(fields()));data.put("mode","DEEPSEEK");data.put("externalConsent",true);
   assertThat(f.browser().send("POST",path(task)+"/housing",Map.of("version",task.path("version").asInt(),"input",data)).statusCode()).isEqualTo(502);
   assertThat(ok(f.browser().get(path(task))).path("version").asInt()).isEqualTo(task.path("version").asInt());
  }
 }
 @Test void guideWithdrawalOrRevisionRequiresFreshReview() throws Exception {
  var f=resident();var task=prepared(f);
  jdbc.sql("UPDATE agent_guide SET version=version+1 WHERE slug='housing'").update();
  assertThat(f.browser().send("POST",path(task)+"/save",confirm(task,true)).statusCode()).isEqualTo(409);
  jdbc.sql("UPDATE agent_guide SET status='WITHDRAWN' WHERE slug='housing'").update();
  try {assertThat(ok(f.browser().get(path(task))).path("housingPreview").path("ready").asBoolean()).isFalse();assertThat(f.browser().send("POST",path(task)+"/save",confirm(task,true)).statusCode()).isEqualTo(409);}
  finally {jdbc.sql("UPDATE agent_guide SET status='PUBLISHED' WHERE slug='housing'").update();}
 }
 @Test void revokedStudentRoleCannotRestoreOrSaveAnOldTask() throws Exception {
  var f=resident();var task=prepared(f);jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='STUDENT'").param("u",user(f.student())).update();
  assertThat(f.browser().get(path(task)).statusCode()).isEqualTo(403);assertThat(f.browser().send("POST",path(task)+"/save",confirm(task,true)).statusCode()).isEqualTo(403);
 }
 @Test void concurrentConfirmedRetriesLinkExactlyOneDraft() throws Exception {
  var f=resident();var task=prepared(f);var body=confirm(task,true);long before=count();
  var a=CompletableFuture.supplyAsync(()->sendUnchecked(f.browser(),"POST",path(task)+"/save",body));var b=CompletableFuture.supplyAsync(()->sendUnchecked(f.browser(),"POST",path(task)+"/save",body));
  assertThat(ok(a.get()).path("business").path("id").asLong()).isEqualTo(ok(b.get()).path("business").path("id").asLong());assertThat(count()).isEqualTo(before+1);
 }
 private record Fixture(String student,Browser browser,Place place) {}
 private Fixture resident()throws Exception{var p=home(login("admin"),"dorm1001");String name=student();checkInStudent(name,p.bed());return new Fixture(name,login(name),p);}
 private void checkInStudent(String name,long bed)throws Exception{ok(login("dorm1001").send("POST","/api/housing/assignments",checkIn(user(name),bed,"助手验收入住")));}
 private void checkout(String name)throws Exception{var row=jdbc.sql("SELECT id,version FROM housing_assignment WHERE current_student_id=:u").param("u",user(name)).query((r,n)->new long[]{r.getLong(1),r.getLong(2)}).single();ok(login("dorm1001").send("POST","/api/housing/assignments/"+row[0]+"/checkout",Map.of("requestKey",key(),"version",row[1],"reason","助手验收退宿")));}
 private Map<String,Object> fields(){return Map.of("reason","私有调宿原因","preferredArea","东校区低楼层","materialNote","私有材料说明");}
 private Map<String,Object> input(Map<String,?> f){return Map.of("message","核对调宿信息","mode","LOCAL","externalConsent",false,"fields",f);}
 private JsonNode prepared(Fixture f)throws Exception{long session=ok(f.browser().send("POST","/api/assistant/sessions",Map.of("requestKey",key()))).path("id").asLong();var task=ok(f.browser().send("POST","/api/assistant/sessions/"+session+"/tasks",Map.of("requestKey",key(),"kind","HOUSING")));long id=task.path("id").asLong();task=ok(f.browser().send("POST","/api/assistant/sessions/"+session+"/tasks/"+id+"/housing",Map.of("version",0,"input",input(fields()))));((tools.jackson.databind.node.ObjectNode)task).put("testSession",session);assertThat(task.path("housingPreview").path("ready").asBoolean()).isTrue();return task;}
 private String path(JsonNode task){return "/api/assistant/sessions/"+task.path("testSession").asLong()+"/tasks/"+task.path("id").asLong();}
 private Map<String,Object> confirm(JsonNode task,boolean confirmed){return Map.of("version",task.path("version").asInt(),"confirmed",confirmed,"expectedStayToken",task.path("housingPreview").path("stayToken").asText());}
 private HttpResponse<String> query(Browser b,String message,Map<String,Object> context)throws Exception{var body=new HashMap<String,Object>(Map.of("message",message,"mode","LOCAL","externalConsent",false));if(context!=null)body.put("context",context);return b.send("POST","/api/assistant/query",body);}
 private long count(){return jdbc.sql("SELECT COUNT(*) FROM housing_transfer").query(Long.class).single();}
    private Place home(Browser admin,String manager)throws Exception{long building=building(admin,1);long room=room(admin,building);long bed=bed(admin,room,"A");ok(admin.send("POST",ROOT+"/scopes",scopeFields(manager,building)));return new Place(building,room,bed,0);}
    private long building(Browser admin,long campus)throws Exception{return ok(admin.send("POST",ROOT+"/buildings",buildingFields(campus))).path("id").asLong();}
    private long room(Browser admin,long building)throws Exception{var fields=new HashMap<String,Object>(Map.of("buildingId",building,"code","R-"+UUID.randomUUID().toString().substring(0,8),"name","101","floorLabel","1 层","version",0,"reason","演示配置原因"));return ok(admin.send("POST",ROOT+"/rooms",fields)).path("id").asLong();}
    private long bed(Browser admin,long room,String code)throws Exception{return ok(admin.send("POST",ROOT+"/beds",Map.of("roomId",room,"code",code,"label","1 号床","version",0,"reason","演示配置原因"))).path("id").asLong();}
    private HashMap<String,Object> buildingFields(long campus){return new HashMap<>(Map.of("campusId",campus,"code","DORM-"+UUID.randomUUID().toString().substring(0,8),"name","演示宿舍楼","provenance","DEMO","version",0,"reason","演示配置原因"));}
    private HashMap<String,Object> checkIn(long student,long bed,String reason){return new HashMap<>(Map.of("requestKey",key(),"studentId",student,"bedId",bed,"reason",reason));}
    private Map<String,Object> scopeFields(String username,long building){return Map.of("userId",user(username),"buildingId",building,"version",0,"reason","演示范围原因");}
    private String key(){return "HK"+UUID.randomUUID().toString().replace("-","");}
    private String student(){return account("hs","住宿测试学生","20260001","STUDENT");}
    private String managerAccount(){return account("hm","测试宿管","dorm1001","DORM_MANAGER");}
    private String account(String prefix,String label,String source,String role){String name=prefix+UUID.randomUUID().toString().replace("-","").substring(0,12);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :u,password_hash,:n,:num,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username=:source").param("u",name).param("n",label).param("num",prefix.toUpperCase()+"-"+name).param("source",source).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",user(name)).param("role",role).update();return name;}
    private String outsiderName(Browser ignored){return jdbc.sql("SELECT username FROM app_user WHERE full_name='测试宿管' ORDER BY id DESC").query(String.class).list().get(0);}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private String identity(String name){return jdbc.sql("SELECT identity_number FROM app_user WHERE username=:u").param("u",name).query(String.class).single();}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private HttpResponse<String> sendUnchecked(Browser actor,String method,String path,Map<String,?> body){try{return actor.send(method,path,body);}catch(Exception ex){throw new RuntimeException(ex);}}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private record Place(long building,long room,long bed,int bedVersion) {}
    private class Browser{
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
