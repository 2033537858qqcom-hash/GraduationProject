package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.agent.DeepSeekClient;
import java.net.*;
import java.net.http.*;
import java.util.*;
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
    "spring.datasource.url=jdbc:h2:mem:campus-agent-repair-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class AgentRepairIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean DeepSeekClient model;
    final JsonMapper json=JsonMapper.builder().build();
    static final String SECRET_TEXT="仅工单可见的故障现象";
    static final String SECRET_CONTACT="owner-only@example.test";
    @BeforeEach void offline() { doReturn(false).when(model).ready(); }
    @Test void repairToolsFollowRoleAndDoNotUseTheStudentPortal() throws Exception {
        assertThat(ok(login("20260001").get("/api/assistant/capabilities")).toString()).contains("my_repairs","repair_status").doesNotContain("repair_dispatch_queue","repair_work_queue");
        assertThat(ok(login("teacher1001").get("/api/assistant/capabilities")).toString()).contains("my_repairs").doesNotContain("my_affairs","repair_dispatch_queue");
        assertThat(ok(login("dispatcher1001").get("/api/assistant/capabilities")).toString()).contains("repair_dispatch_queue","repair_status").doesNotContain("my_repairs","my_affairs");
        assertThat(ok(login("technician1001").get("/api/assistant/capabilities")).toString()).contains("repair_work_queue","repair_status").doesNotContain("my_repairs","repair_dispatch_queue");
        assertThat(ok(login("admin").get("/api/assistant/capabilities")).toString()).doesNotContain("my_repairs","repair_dispatch_queue","repair_work_queue","repair_status");
    }
    @Test void guideAndPersonalProgressStayInsideTheCallerScope() throws Exception {
        long location=place(1,"东区开放走廊"); long id=submitted(login("20260001"),location,SECRET_TEXT);
        var guide=ok(query(login("20260001"),"报修怎么办理",null));
        assertThat(guide.toString()).contains("校园报修办理指南","助手不能猜测地点").doesNotContain(SECRET_TEXT,SECRET_CONTACT);
        var mine=ok(query(login("20260001"),"我的报修进度",null));
        assertThat(mine.toString()).contains("REPAIR:"+id,"待后勤分诊","东区开放走廊","/repairs/mine?id="+id+"&tab=details").doesNotContain(SECRET_TEXT,SECRET_CONTACT,"my_affairs");
        var teacher=ok(query(login("teacher1001"),"我的报修进度",null));
        assertThat(teacher.toString()).doesNotContain("REPAIR:"+id,SECRET_TEXT,SECRET_CONTACT);
        assertThat(query(login("20260002"),"当前报修的处理进度",Map.of("type","REPAIR_MINE","id",id)).statusCode()).isEqualTo(404);
    }
    @Test void roleQueuesReturnStatusAndLinksWithoutPrivateContent() throws Exception {
        long east=place(1,"东区开放走廊"),west=place(2,"西区不在范围");
        jdbc.sql("INSERT INTO repair_staff_scope(user_id,capability,campus_id,category) VALUES(:u,'DISPATCH',2,'ELECTRICITY')").param("u",user("dispatcher1001")).update();
        long draft=saved(login("20260001"),east,false); long visible=submitted(login("20260001"),east,SECRET_TEXT); long outside=submitted(login("teacher1001"),west,"西区现象");
        jdbc.sql("UPDATE repair_staff_scope SET enabled=FALSE WHERE user_id=:u AND campus_id=2").param("u",user("dispatcher1001")).update();
        var dispatch=ok(query(login("dispatcher1001"),"待分诊报修",null));
        assertThat(dispatch.toString()).contains("REPAIR:"+visible,"/logistics/repairs/dispatch?id="+visible+"&tab=details").doesNotContain("REPAIR:"+draft,"REPAIR:"+outside,SECRET_TEXT,SECRET_CONTACT,"西区现象");
        var before=ok(query(login("technician1001"),"我的维修任务",null)); assertThat(before.toString()).doesNotContain("REPAIR:"+visible);
        var assigned=act(login("dispatcher1001"),"dispatch",visible,"assign",Map.of("technicianId",user("technician1001")));
        assertThat(assigned.path("status").asText()).isEqualTo("ASSIGNED");
        var work=ok(query(login("technician1001"),"待接单的维修任务",null));
        assertThat(work.toString()).contains("REPAIR:"+visible,"待维修接单","/logistics/repairs/tasks?id="+visible+"&tab=details").doesNotContain(SECRET_TEXT,SECRET_CONTACT);
        assertThat(query(login("dispatcher1001"),"帮我直接派单",null).body()).contains("不能代替提交或审批");
        assertThat(query(login("20260001"),"其他同学的报修进度",null).body()).contains("不能查询无关人员资料");
    }
    @Test void previewAsksForLocationAndContactWithoutCreatingARepair() throws Exception {
        assertThat(new Browser().get("/api/assistant/repair/prepare").statusCode()).isEqualTo(401);
        var student=login("20260001");
        var noCsrf=student.http.send(student.builder("/api/assistant/repair/prepare").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input(Map.of())))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        for(String role:List.of("dispatcher1001","technician1001","admin")) assertThat(prepare(login(role),input(Map.of())).statusCode()).isEqualTo(403);
        long before=count(); var result=ok(prepare(student,input(Map.of())));
        assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.path("missing").toString()).contains("category","title","description","locationId","contact");
        assertThat(result.path("source").path("slug").asText()).isEqualTo("repair"); assertThat(count()).isEqualTo(before); verify(model,never()).prepareRepair(anyString());
    }
    @Test void modelCanOnlySupplyExplicitFaultFields() throws Exception {
        long location=place(1,"东区开放走廊"); var fields=complete(location); fields.remove("category"); fields.remove("title"); fields.remove("description");
        online(Map.of("category","ELECTRICITY","title","照明故障","description","灯不亮"));
        var body=input(fields); body.put("mode","DEEPSEEK"); body.put("externalConsent",true); body.put("message","走廊灯不亮，标题写照明故障，类别是电气");
        long before=count(); var result=ok(prepare(login("20260001"),body));
        assertThat(result.path("ready").asBoolean()).isTrue(); assertThat(result.path("fields").path("contact").asText()).isEqualTo(SECRET_CONTACT);
        assertThat(result.path("fields").path("locationId").asLong()).isEqualTo(location); assertThat(result.path("locationName").asText()).contains("东区开放走廊");
        verify(model).prepareRepair("走廊灯不亮，标题写照明故障，类别是电气"); assertThat(count()).isEqualTo(before);
        var audit=jdbc.sql("SELECT * FROM agent_run WHERE id=:id").param("id",result.path("runId").asText()).query().singleRow();
        assertThat(audit.toString()).contains("prepare_repair_fields").doesNotContain(SECRET_CONTACT,"灯不亮");
    }
    @Test void modelCannotChooseLocationContactOrCommands() throws Exception {
        var student=login("20260001"); long location=place(1,"东区开放走廊"); long before=count();
        for(var patch:List.of(Map.of("locationId",location),Map.of("contact",SECRET_CONTACT),Map.of("locationDetail","模型猜测的房间"),Map.of("status","SUBMITTED"),Map.of("category","APPROVED"),Map.of("title","x".repeat(101)))) {
            online(patch); var body=input(complete(location)); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
            assertThat(prepare(student,body).statusCode()).as(patch.toString()).isEqualTo(502);
        }
        doReturn(new Plan(List.of(new Call("submit_repair",json.valueToTree(Map.of()))),1,1)).when(model).prepareRepair(anyString());
        var body=input(complete(location)); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
        assertThat(prepare(student,body).statusCode()).isEqualTo(502); assertThat(count()).isEqualTo(before);
    }
    @Test void invalidLocationAndWithdrawnGuideBlockReadiness() throws Exception {
        var student=login("20260001"); long location=place(1,"即将停用的地点"); var fields=complete(location); fields.put("contact","bad-contact");
        assertThat(ok(prepare(student,input(fields))).toString()).contains("手机号码或邮箱");
        jdbc.sql("UPDATE repair_location SET enabled=FALSE WHERE id=:id").param("id",location).update();
        assertThat(ok(prepare(student,input(complete(location)))).toString()).contains("当前开放地点");
        jdbc.sql("UPDATE agent_guide SET status='WITHDRAWN' WHERE slug='repair'").update();
        try { var result=ok(prepare(student,input(complete(place(1,"仍开放的地点"))))); assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.toString()).contains("指南已撤下"); }
        finally { jdbc.sql("UPDATE agent_guide SET status='PUBLISHED' WHERE slug='repair'").update(); }
    }
    @Test void confirmedSaveCreatesOneOwnedDraftAndRestoreRechecksTheLocation() throws Exception {
        var student=login("20260001"); long session=session(student); var task=createTask(student,session); long location=place(1,"东区开放走廊");
        long before=count(),notices=jdbc.sql("SELECT COUNT(*) FROM user_notice").query(Long.class).single();
        var prepared=ok(student.send("POST",taskPath(session,task.path("id").asLong())+"/repair",Map.of("version",0,"input",input(complete(location)))));
        assertThat(prepared.path("repairPreview").path("ready").asBoolean()).isTrue(); assertThat(count()).isEqualTo(before);
        assertThat(student.send("POST",taskPath(session,task.path("id").asLong())+"/save",Map.of("version",0,"confirmed",true)).statusCode()).isEqualTo(409);
        var saved=ok(student.send("POST",taskPath(session,task.path("id").asLong())+"/save",Map.of("version",prepared.path("version").asInt(),"confirmed",true)));
        var again=ok(student.send("POST",taskPath(session,task.path("id").asLong())+"/save",Map.of("version",saved.path("version").asInt(),"confirmed",true)));
        long id=saved.path("business").path("id").asLong();
        assertThat(again.path("business").path("id").asLong()).isEqualTo(id); assertThat(saved.path("business").path("status").asText()).isEqualTo("DRAFT");
        assertThat(saved.toString()).doesNotContain(SECRET_CONTACT,SECRET_TEXT); assertThat(count()).isEqualTo(before+1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice").query(Long.class).single()).isEqualTo(notices);
        assertThat(login("20260002").get(taskPath(session,task.path("id").asLong())).statusCode()).isEqualTo(404);
        assertThat(login("teacher1001").send("POST","/api/assistant/sessions/"+session+"/tasks",Map.of("requestKey",UUID.randomUUID().toString(),"kind","REPAIR")).statusCode()).isEqualTo(404);
        jdbc.sql("UPDATE repair_location SET enabled=FALSE WHERE id=:id").param("id",location).update();
        var second=createTask(student,session); var stale=ok(student.send("POST",taskPath(session,second.path("id").asLong())+"/repair",Map.of("version",0,"input",input(complete(location)))));
        assertThat(stale.path("repairPreview").path("ready").asBoolean()).isFalse();
        assertThat(student.send("POST",taskPath(session,second.path("id").asLong())+"/save",Map.of("version",stale.path("version").asInt(),"confirmed",true)).statusCode()).isEqualTo(409);
        assertThat(count()).isEqualTo(before+1);
    }
    private long count() { return jdbc.sql("SELECT COUNT(*) FROM repair_request").query(Long.class).single(); }
    private long user(String name) { return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single(); }
    private void online(Map<String,?> patch) { doReturn(true).when(model).ready(); doReturn(new Plan(List.of(new Call("prepare_repair_fields",json.valueToTree(patch))),40,8)).when(model).prepareRepair(anyString()); }
    private long place(long campus,String name) throws Exception { return ok(login("admin").send("POST","/api/admin/repair-settings/locations",Map.of("campusId",campus,"code",UUID.randomUUID().toString(),"name",name,"kind","CAMPUS_PUBLIC","provenance","DEMO","version",0,"reason","报修助手测试"))).path("id").asLong(); }
    private HashMap<String,Object> complete(long location) { return new HashMap<>(Map.of("category","ELECTRICITY","title","照明故障","description",SECRET_TEXT,"locationId",location,"locationDetail","走廊东侧","contact",SECRET_CONTACT,"contactWindow","课后")); }
    private HashMap<String,Object> input(Map<String,?> fields) { return new HashMap<>(Map.of("message","准备报修","mode","LOCAL","externalConsent",false,"fields",fields)); }
    private long saved(Browser owner,long location,boolean submit) throws Exception { var row=ok(owner.send("POST","/api/repairs/mine",repairDraft(location))); return submit?act(owner,"mine",row.path("id").asLong(),"submit",Map.of()).path("id").asLong():row.path("id").asLong(); }
    private long submitted(Browser owner,long location,String description) throws Exception { var body=repairDraft(location); body.put("description",description); var row=ok(owner.send("POST","/api/repairs/mine",body)); return act(owner,"mine",row.path("id").asLong(),"submit",Map.of()).path("id").asLong(); }
    private HashMap<String,Object> repairDraft(long location) { return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",0,"locationId",location,"category","ELECTRICITY","title","照明故障","description",SECRET_TEXT,"locationDetail","走廊东侧","contact",SECRET_CONTACT,"contactWindow","课后")); }
    private JsonNode act(Browser browser,String view,long id,String action,Map<String,?> extra) throws Exception { int version=ok(browser.get("/api/repairs/"+view+"/"+id)).path("version").asInt(); var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"version",version,"comment","助手测试处理")); body.putAll(extra); return ok(browser.send("POST","/api/repairs/"+view+"/"+id+"/"+action,body)); }
    private HttpResponse<String> prepare(Browser student,Map<String,?> body) throws Exception { return student.send("POST","/api/assistant/repair/prepare",body); }
    private HttpResponse<String> query(Browser browser,String message,Map<String,?> context) throws Exception { var body=new HashMap<String,Object>(Map.of("message",message,"mode","LOCAL","externalConsent",false)); if(context!=null) body.put("context",context); return browser.send("POST","/api/assistant/query",body); }
    private long session(Browser browser) throws Exception { return ok(browser.send("POST","/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong(); }
    private JsonNode createTask(Browser browser,long session) throws Exception { return ok(browser.send("POST","/api/assistant/sessions/"+session+"/tasks",Map.of("requestKey",UUID.randomUUID().toString(),"kind","REPAIR"))); }
    private String taskPath(long session,long task) { return "/api/assistant/sessions/"+session+"/tasks/"+task; }
    private JsonNode ok(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body()); }
    private Browser login(String name) throws Exception { var browser=new Browser(); browser.csrf(); ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026"))); browser.csrf(); return browser; }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String header,token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception { var data=json.readTree(get("/api/auth/csrf").body()); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception { return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
