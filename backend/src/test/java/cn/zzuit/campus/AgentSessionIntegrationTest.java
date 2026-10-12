package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.agent.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-agent-session-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class AgentSessionIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean DeepSeekClient model;
    @MockitoSpyBean CertificateService certificates;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void offline() { doReturn(false).when(model).ready(); }
    @Test void sessionsRequireLoginCsrfAndOwnedAccessEvenForAdmin() throws Exception {
        assertThat(new Browser().get("/api/assistant/sessions").statusCode()).isEqualTo(401); var student=login("20260001"); long id=session(student);
        assertThat(login("20260002").get(path(id)).statusCode()).isEqualTo(404); assertThat(login("admin").get(path(id)).statusCode()).isEqualTo(404);
        var noCsrf=student.http.send(student.builder("/api/assistant/sessions").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"requestKey\":\"csrf-test-key\"}")).build(),HttpResponse.BodyHandlers.ofString()); assertThat(noCsrf.statusCode()).isEqualTo(403);
    }
    @Test void sessionCreationIsIdempotentAndListingsAreBounded() throws Exception {
        var student=login("20260001"); var body=Map.of("requestKey",UUID.randomUUID().toString()); var first=ok(student.send("POST","/api/assistant/sessions",body)); var again=ok(student.send("POST","/api/assistant/sessions",body));
        assertThat(again.path("id").asLong()).isEqualTo(first.path("id").asLong()); assertThat(ok(student.get("/api/assistant/sessions?page=1&pageSize=1")).path("items").size()).isEqualTo(1);
        assertThat(student.get("/api/assistant/sessions?page=0").statusCode()).isEqualTo(400); assertThat(student.get("/api/assistant/sessions?pageSize=21").statusCode()).isEqualTo(400);
    }
    @Test void historicalQuestionsPersistWithoutPrivateAnswersAndDuplicateRequestsDoNotRunAgain() throws Exception {
        var student=login("20260001"); long id=session(student); var body=query("我有多少未读消息"); var first=ok(student.send("POST",path(id)+"/query",body));
        var duplicate=ok(student.send("POST",path(id)+"/query",body)); assertThat(duplicate.path("repeated").asBoolean()).isTrue(); assertThat(duplicate.path("answer").isNull()).isTrue();
        var restored=ok(login("20260001").get(path(id))); assertThat(restored.path("turns").size()).isEqualTo(1); assertThat(restored.path("turns").path(0).path("question").asText()).isEqualTo("我有多少未读消息");
        assertThat(restored.toString()).doesNotContain("results","items","unreadCount","externalConsent"); assertThat(first.path("answer").path("results").size()).isEqualTo(1);
        body.put("input",Map.of("message","换个问题","mode","LOCAL","externalConsent",false)); assertThat(student.send("POST",path(id)+"/query",body).statusCode()).isEqualTo(409); verify(model,never()).plan(anyString(),anyList(),any());
    }
    @Test void realModelContractReceivesOnlyTheCurrentQuestionAndIsNotReplayedOnRecovery() throws Exception {
        var student=login("20260001"); long id=session(student); online(); var body=query("我有多少未读消息"); body.put("input",Map.of("message","我有多少未读消息","mode","DEEPSEEK","externalConsent",true));
        ok(student.send("POST",path(id)+"/query",body)); ok(login("20260001").get(path(id))); ok(student.send("POST",path(id)+"/query",body));
        verify(model,times(1)).plan(eq("我有多少未读消息"),anyList(),isNull());
    }
    @Test void failedAndInterruptedQueriesRemainVisibleAndNeverAutomaticallyRepeat() throws Exception {
        var student=login("20260001"); long id=session(student); online(); doThrow(new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_UNAVAILABLE","模型不可用")).when(model).plan(anyString(),anyList(),any());
        var body=query("消息查询失败演示"); body.put("input",Map.of("message","消息查询失败演示","mode","DEEPSEEK","externalConsent",true)); assertThat(student.send("POST",path(id)+"/query",body).statusCode()).isEqualTo(502);
        assertThat(ok(student.get(path(id))).path("turns").path(0).path("status").asText()).isEqualTo("FAILED"); ok(student.send("POST",path(id)+"/query",body)); verify(model,times(1)).plan(anyString(),anyList(),any());
        String key=UUID.randomUUID().toString(); jdbc.sql("INSERT INTO agent_turn(session_id,request_key,question,mode,status) VALUES(:s,:k,'中断演示','LOCAL','RUNNING')").param("s",id).param("k",key).update();
        var result=ok(student.send("POST",path(id)+"/query",Map.of("requestKey",key,"input",Map.of("message","中断演示","mode","LOCAL","externalConsent",false)))); assertThat(result.path("turn").path("status").asText()).isEqualTo("INTERRUPTED"); assertThat(result.path("answer").isNull()).isTrue();
    }
    @Test void olderTurnsArePagedWithoutRepeatingTheLastPage() throws Exception {
        var student=login("20260001"); long id=session(student);
        for(int i=0;i<23;i++) jdbc.sql("INSERT INTO agent_turn(session_id,request_key,question,mode,status) VALUES(:s,:k,:q,'LOCAL','SUCCESS')").param("s",id).param("k",UUID.randomUUID().toString()).param("q","历史提问 "+i).update();
        var last=ok(student.get(path(id))); assertThat(last.path("turns").size()).isEqualTo(20); assertThat(last.path("hasOlder").asBoolean()).isTrue();
        var older=ok(student.get(path(id)+"?before="+last.path("turns").path(0).path("id").asLong())); assertThat(older.path("turns").size()).isEqualTo(3); assertThat(older.path("hasOlder").asBoolean()).isFalse();
    }
    @Test void proofTaskRestoresOnlyLocalFieldsWithFreshIdentityAndNoConfirmationOrBusinessWrite() throws Exception {
        var student=login("20260001"); long id=session(student); var task=createTask(student,id,"CERTIFICATE"); long before=count("certificate_request");
        var prepared=proof(student,id,task,"实习身份核验"); var restored=ok(login("20260001").get(taskPath(id,task.path("id").asLong())));
        assertThat(restored.path("certificatePreview").path("fields").path("purpose").asText()).isEqualTo("实习身份核验"); assertThat(restored.path("certificatePreview").path("mode").asText()).isEqualTo("LOCAL");
        assertThat(restored.path("version").asInt()).isEqualTo(prepared.path("version").asInt()); assertThat(restored.path("business").isNull()).isTrue(); assertThat(count("certificate_request")).isEqualTo(before);
        String fields=jdbc.sql("SELECT fields_json FROM agent_task WHERE id=:id").param("id",task.path("id").asLong()).query(String.class).single(); assertThat(fields).contains("实习身份核验").doesNotContain("identity","confirmed","20260001","陈同学");
        assertThat(login("20260002").get(taskPath(id,task.path("id").asLong())).statusCode()).isEqualTo(404);
    }
    @Test void proofSaveRequiresPersonalConfirmationAndRecoveryCannotCreateAnotherDraft() throws Exception {
        var student=login("20260001"); long id=session(student); var task=proof(student,id,createTask(student,id,"CERTIFICATE"),"实习核验"); long before=count("certificate_request"),taskId=task.path("id").asLong();
        var body=confirmation(task); body.put("confirmed",false); assertThat(student.send("POST",taskPath(id,taskId)+"/save",body).statusCode()).isEqualTo(400); assertThat(count("certificate_request")).isEqualTo(before);
        body.put("confirmed",true); var saved=ok(student.send("POST",taskPath(id,taskId)+"/save",body)); var duplicate=ok(student.send("POST",taskPath(id,taskId)+"/save",body)); var restored=ok(login("20260001").get(taskPath(id,taskId)));
        assertThat(saved.path("stage").asText()).isEqualTo("LINKED"); assertThat(saved.path("business").path("status").asText()).isEqualTo("DRAFT"); assertThat(duplicate.path("business").path("id").asLong()).isEqualTo(saved.path("business").path("id").asLong()); assertThat(restored.path("business")).isEqualTo(saved.path("business")); assertThat(count("certificate_request")).isEqualTo(before+1);
    }
    @Test void linkedTasksReReadTheOriginalBusinessVersionAndStatus() throws Exception {
        var student=login("20260001"); long id=session(student); var task=proof(student,id,createTask(student,id,"CERTIFICATE"),"实习核验"); var saved=ok(student.send("POST",taskPath(id,task.path("id").asLong())+"/save",confirmation(task))); long business=saved.path("business").path("id").asLong();
        ok(student.send("POST","/api/certificates/mine/"+business+"/submit",Map.of("version",0,"comment","本人提交演示")));
        var recovered=ok(student.get(taskPath(id,task.path("id").asLong()))); assertThat(recovered.path("business").path("status").asText()).isEqualTo("SUBMITTED"); assertThat(recovered.path("business").path("version").asInt()).isEqualTo(1);
    }
    @Test void changedIdentityAndWithdrawnGuideRequireFreshReviewBeforeSaving() throws Exception {
        var student=login("20260001"); long id=session(student); var task=proof(student,id,createTask(student,id,"CERTIFICATE"),"实习核验"); String original=jdbc.sql("SELECT full_name FROM app_user WHERE username='20260001'").query(String.class).single(); long before=count("certificate_request");
        jdbc.sql("UPDATE app_user SET full_name='登记更新演示' WHERE username='20260001'").update();
        try { assertThat(student.send("POST",taskPath(id,task.path("id").asLong())+"/save",confirmation(task)).statusCode()).isEqualTo(409); assertThat(ok(student.get(taskPath(id,task.path("id").asLong()))).path("certificatePreview").path("identity").path("studentName").asText()).isEqualTo("登记更新演示"); }
        finally { jdbc.sql("UPDATE app_user SET full_name=:n WHERE username='20260001'").param("n",original).update(); }
        jdbc.sql("UPDATE agent_guide SET status='WITHDRAWN' WHERE slug='certificate'").update();
        try { assertThat(ok(student.get(taskPath(id,task.path("id").asLong()))).path("certificatePreview").path("ready").asBoolean()).isFalse(); assertThat(student.send("POST",taskPath(id,task.path("id").asLong())+"/save",confirmation(task)).statusCode()).isEqualTo(409); }
        finally { jdbc.sql("UPDATE agent_guide SET status='PUBLISHED' WHERE slug='certificate'").update(); }
        assertThat(count("certificate_request")).isEqualTo(before);
    }
    @Test void staleTaskVersionsCannotOverwriteNewerReviewedFields() throws Exception {
        var student=login("20260001"); long id=session(student); var initial=createTask(student,id,"CERTIFICATE"); proof(student,id,initial,"新用途");
        var response=student.send("POST",taskPath(id,initial.path("id").asLong())+"/certificate",Map.of("version",0,"input",prepare(Map.of("purpose","旧窗口用途")))); assertThat(response.statusCode()).isEqualTo(409);
        assertThat(ok(student.get(taskPath(id,initial.path("id").asLong()))).path("certificatePreview").path("fields").path("purpose").asText()).isEqualTo("新用途");
    }
    @Test void leaveTaskRestoresReviewedDatesAndContactAndNeedsManualSave() throws Exception {
        var student=login("20260001"); long id=session(student); var task=createTask(student,id,"LEAVE"); String date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7).toString(); long before=count("leave_request");
        var fields=Map.of("reasonType","PERSONAL","reason","公开校园活动演示","contact","demo@example.test","startDate",date,"startTime","09:00","endDate",date,"endTime","17:00");
        var prepared=ok(student.send("POST",taskPath(id,task.path("id").asLong())+"/leave",Map.of("version",0,"input",prepare(fields)))); var restored=ok(login("20260001").get(taskPath(id,task.path("id").asLong())));
        assertThat(restored.path("leavePreview").path("fields").path("contact").asText()).isEqualTo("demo@example.test"); assertThat(restored.path("leavePreview").path("ready").asBoolean()).isTrue(); assertThat(count("leave_request")).isEqualTo(before);
        var saved=ok(student.send("POST",taskPath(id,task.path("id").asLong())+"/save",Map.of("version",prepared.path("version").asInt(),"confirmed",true))); assertThat(saved.path("business").path("status").asText()).isEqualTo("DRAFT"); assertThat(count("leave_request")).isEqualTo(before+1);
    }
    @Test void roleChangeFiltersTaskMetadataAndBlocksOldTaskAccess() throws Exception {
        var student=login("20260002"); long id=session(student); var task=proof(student,id,createTask(student,id,"CERTIFICATE"),"私有演示用途");
        jdbc.sql("DELETE FROM user_role WHERE user_id=(SELECT id FROM app_user WHERE username='20260002') AND role_code='STUDENT'").update(); jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,'TEACHER' FROM app_user WHERE username='20260002'").update(); jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE username='20260002'").update();
        try { assertThat(student.get(path(id)).statusCode()).isEqualTo(401); var changed=login("20260002"); assertThat(ok(changed.get(path(id))).path("tasks").isEmpty()).isTrue(); assertThat(changed.get(taskPath(id,task.path("id").asLong())).statusCode()).isEqualTo(403); }
        finally { jdbc.sql("DELETE FROM user_role WHERE user_id=(SELECT id FROM app_user WHERE username='20260002') AND role_code='TEACHER'").update(); jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,'STUDENT' FROM app_user WHERE username='20260002'").update(); jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE username='20260002'").update(); }
    }
    @Test void businessCreationAndTaskAssociationRollbackTogether() throws Exception {
        var student=login("20260001"); long id=session(student); var task=proof(student,id,createTask(student,id,"CERTIFICATE"),"事务回滚演示"); long before=count("certificate_request");
        doAnswer(invocation->{ invocation.callRealMethod(); throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEMO_ROLLBACK","事务回滚演示"); }).when(certificates).save(any(),isNull(),any());
        assertThat(student.send("POST",taskPath(id,task.path("id").asLong())+"/save",confirmation(task)).statusCode()).isEqualTo(503); assertThat(count("certificate_request")).isEqualTo(before); assertThat(ok(student.get(taskPath(id,task.path("id").asLong()))).path("stage").asText()).isEqualTo("PREVIEW");
    }
    private long count(String table) { return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single(); }
    private void online() { doReturn(true).when(model).ready(); doReturn(new Plan(List.of(new Call("my_notice_summary",json.valueToTree(Map.of()))),30,5)).when(model).plan(anyString(),anyList(),any()); }
    private long session(Browser browser) throws Exception { return ok(browser.send("POST","/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong(); }
    private String path(long id) { return "/api/assistant/sessions/"+id; }
    private String taskPath(long session,long task) { return path(session)+"/tasks/"+task; }
    private JsonNode createTask(Browser browser,long session,String kind) throws Exception { return ok(browser.send("POST",path(session)+"/tasks",Map.of("requestKey",UUID.randomUUID().toString(),"kind",kind))); }
    private JsonNode proof(Browser browser,long session,JsonNode task,String purpose) throws Exception { return ok(browser.send("POST",taskPath(session,task.path("id").asLong())+"/certificate",Map.of("version",task.path("version").asInt(),"input",prepare(Map.of("purpose",purpose))))); }
    private Map<String,Object> prepare(Map<String,?> fields) { return Map.of("message","核对预览演示","mode","LOCAL","externalConsent",false,"fields",fields); }
    private HashMap<String,Object> query(String message) { return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"input",Map.of("message",message,"mode","LOCAL","externalConsent",false))); }
    private HashMap<String,Object> confirmation(JsonNode task) { return new HashMap<>(Map.of("version",task.path("version").asInt(),"confirmed",true,"expectedIdentity",json.convertValue(task.path("certificatePreview").path("identity"),Map.class))); }
    private JsonNode ok(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body()); }
    private Browser login(String username) throws Exception { var browser=new Browser(); browser.csrf(); ok(browser.send("POST","/api/auth/login",Map.of("username",username,"password","Demo@2026"))); browser.csrf(); return browser; }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String header,token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception { var data=json.readTree(get("/api/auth/csrf").body()); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception { return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
