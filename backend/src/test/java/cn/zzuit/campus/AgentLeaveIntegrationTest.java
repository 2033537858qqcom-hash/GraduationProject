package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.agent.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
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
    "spring.datasource.url=jdbc:h2:mem:campus-agent-leave-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class AgentLeaveIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean DeepSeekClient model;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void offline() { doReturn(false).when(model).ready(); }
    @Test void requiresLoginCsrfAndStudentLeavePermission() throws Exception {
        assertThat(new Browser().get("/api/assistant/leave/prepare").statusCode()).isEqualTo(401);
        var student=login("20260001");
        var noCsrf=student.http.send(student.builder("/api/assistant/leave/prepare").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input(Map.of())))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        for(String role:List.of("teacher1001","admin","counselor1001","affairs1001")) assertThat(prepare(login(role),input(Map.of())).statusCode()).isEqualTo(403);
        verify(model,never()).prepareLeave(anyString());
    }
    @Test void missingDatesAndTimesAreAskedForWithoutCreatingAnApplication() throws Exception {
        long before=count(); var result=ok(prepare(login("20260001"),input(Map.of())));
        assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.path("missing").size()).isEqualTo(7);
        assertThat(result.path("fields").path("startTime").asText()).isEmpty();
        assertThat(result.path("source").path("version").asInt()).isEqualTo(2);
        assertThat(count()).isEqualTo(before); verify(model,never()).prepareLeave(anyString());
    }
    @Test void supplementsMergeLocallyAndOnlyCurrentTextReachesTheModel() throws Exception {
        var fields=complete(); fields.remove("startTime"); fields.remove("endTime");
        online(Map.of("startTime","09:00","endTime","17:00"));
        var body=input(fields); body.put("mode","DEEPSEEK"); body.put("externalConsent",true); body.put("message","开始上午九点，结束下午五点");
        long before=count(); var result=ok(prepare(login("20260001"),body));
        assertThat(result.path("ready").asBoolean()).isTrue(); assertThat(result.path("fields").path("reason").asText()).isEqualTo("参加家庭活动（演示）");
        assertThat(result.path("fields").path("contact").asText()).isEqualTo("demo@example.test");
        verify(model).prepareLeave("开始上午九点，结束下午五点");
        assertThat(count()).isEqualTo(before);
        var audit=jdbc.sql("SELECT * FROM agent_run WHERE id=:id").param("id",result.path("runId").asText()).query().singleRow();
        assertThat(audit.toString()).contains("collect_leave_fields").doesNotContain("家庭活动","example.test","上午九点");
    }
    @Test void datesWithoutTimesStayIncompleteAfterModelExtraction() throws Exception {
        var date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7).toString(); online(Map.of("startDate",date,"endDate",date));
        var body=input(Map.of()); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
        var result=ok(prepare(login("20260001"),body));
        assertThat(result.path("fields").path("startTime").asText()).isEmpty(); assertThat(result.path("fields").path("endTime").asText()).isEmpty();
        assertThat(result.path("missing").toString()).contains("startTime","endTime"); assertThat(result.path("ready").asBoolean()).isFalse();
    }
    @Test void invalidModelFieldsCannotSupplyIdentityContactsStatusOrCommands() throws Exception {
        var student=login("20260001"); long before=count();
        for(var patch:List.of(Map.of("studentId",2),Map.of("contact","model@example.test"),Map.of("status","APPROVED"),Map.of("startDate","2026-02-30"),Map.of("startTime","24:00"),Map.of("reasonType","APPROVED"))) {
            online(patch); var body=input(complete()); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
            var result=prepare(student,body); assertThat(result.statusCode()).isEqualTo(502); assertThat(result.body()).contains("MODEL_LEAVE_FIELDS_REJECTED");
        }
        doReturn(new Plan(List.of(new Call("submit_leave",json.valueToTree(Map.of()))),1,1)).when(model).prepareLeave(anyString());
        var body=input(complete()); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
        assertThat(prepare(student,body).statusCode()).isEqualTo(502); assertThat(count()).isEqualTo(before);
    }
    @Test void missingConsentAndConnectionPreventModelUse() throws Exception {
        var student=login("20260001"); var body=input(Map.of()); body.put("mode","DEEPSEEK");
        assertThat(prepare(student,body).statusCode()).isEqualTo(400);
        body.put("externalConsent",true); assertThat(prepare(student,body).statusCode()).isEqualTo(503);
        verify(model,never()).prepareLeave(anyString());
    }
    @Test void ordinaryBusinessRulesAndFutureDateAreCheckedInPreview() throws Exception {
        var student=login("20260001");
        var contact=complete(); contact.put("contact","bad-contact"); assertThat(ok(prepare(student,input(contact))).toString()).contains("请填写可联系的手机号码或邮箱");
        var reverse=complete(); reverse.put("endTime","07:00"); assertThat(ok(prepare(student,input(reverse))).toString()).contains("结束时间须晚于开始时间");
        var longLeave=complete(); longLeave.put("endDate",LocalDate.parse((String)longLeave.get("startDate")).plusDays(31).toString());
        assertThat(ok(prepare(student,input(longLeave))).toString()).contains("30 天");
        var past=complete(); past.put("startDate","2020-01-01"); past.put("endDate","2020-01-01"); assertThat(ok(prepare(student,input(past))).toString()).contains("开始时间已到");
        assertThat(ok(prepare(student,input(complete()))).path("duration").asText()).isEqualTo("8 小时 ");
    }
    @Test void onlyExplicitOrdinarySaveCreatesOneOwnedDraftAndNoNotice() throws Exception {
        var student=login("20260001"); var fields=complete(); long before=count(),notices=jdbc.sql("SELECT COUNT(*) FROM user_notice").query(Long.class).single();
        ok(prepare(student,input(fields))); assertThat(count()).isEqualTo(before);
        var body=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"reasonType",fields.get("reasonType"),"reason",fields.get("reason"),"contact",fields.get("contact"),
            "startsAt",fields.get("startDate")+"T"+fields.get("startTime")+":00","endsAt",fields.get("endDate")+"T"+fields.get("endTime")+":00","version",0);
        var saved=ok(student.send("POST","/api/leave/mine",body)); var retry=ok(student.send("POST","/api/leave/mine",body));
        assertThat(retry.path("id").asLong()).isEqualTo(saved.path("id").asLong()); assertThat(saved.path("status").asText()).isEqualTo("DRAFT"); assertThat(saved.path("version").asInt()).isZero();
        assertThat(count()).isEqualTo(before+1); assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice").query(Long.class).single()).isEqualTo(notices);
        assertThat(login("20260002").get("/api/leave/mine/"+saved.path("id").asLong()).statusCode()).isEqualTo(404);
    }
    @Test void withdrawnGuideStopsAssistantReadiness() throws Exception {
        jdbc.sql("UPDATE agent_guide SET status='WITHDRAWN' WHERE slug='leave'").update();
        try { var result=ok(prepare(login("20260001"),input(complete()))); assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.path("source").isNull()).isTrue(); assertThat(result.toString()).contains("指南已撤下"); }
        finally { jdbc.sql("UPDATE agent_guide SET status='PUBLISHED' WHERE slug='leave'").update(); }
    }
    @Test void identityChangeDuringExtractionBlocksPreviewDelivery() throws Exception {
        var student=login("20260002"); doReturn(true).when(model).ready();
        doAnswer(i->{ jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE username='20260002'").update(); return new Plan(List.of(new Call("collect_leave_fields",json.valueToTree(Map.of()))),1,1); }).when(model).prepareLeave(anyString());
        var body=input(Map.of()); body.put("mode","DEEPSEEK"); body.put("externalConsent",true); long before=count();
        assertThat(prepare(student,body).statusCode()).isEqualTo(401); assertThat(count()).isEqualTo(before);
    }
    @Test void invalidClientFieldsAreRejectedBeforeCallingTheModel() throws Exception {
        var fields=complete(); fields.put("startDate","2026-02-30"); var body=input(fields); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
        assertThat(prepare(login("20260001"),body).statusCode()).isEqualTo(400); verify(model,never()).prepareLeave(anyString());
    }
    private long count() { return jdbc.sql("SELECT COUNT(*) FROM leave_request").query(Long.class).single(); }
    private void online(Map<String,?> patch) { doReturn(true).when(model).ready(); doReturn(new Plan(List.of(new Call("collect_leave_fields",json.valueToTree(patch))),50,10)).when(model).prepareLeave(anyString()); }
    private HashMap<String,Object> complete() { String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7).toString(); return new HashMap<>(Map.of("reasonType","PERSONAL","reason","参加家庭活动（演示）","contact","demo@example.test","startDate",day,"startTime","09:00","endDate",day,"endTime","17:00")); }
    private HashMap<String,Object> input(Map<String,?> fields) { return new HashMap<>(Map.of("message","准备请假","mode","LOCAL","externalConsent",false,"fields",fields)); }
    private HttpResponse<String> prepare(Browser student,Map<String,?> body) throws Exception { return student.send("POST","/api/assistant/leave/prepare",body); }
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
