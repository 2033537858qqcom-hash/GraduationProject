package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.agent.*;
import cn.zzuit.campus.agent.AgentModels.*;
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
    "spring.datasource.url=jdbc:h2:mem:campus-agent-certificate-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class AgentCertificateIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean DeepSeekClient model;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void offline() { doReturn(false).when(model).ready(); }
    @Test void requiresLoginCsrfAndStudentCertificatePermission() throws Exception {
        assertThat(new Browser().get("/api/assistant/certificate/prepare").statusCode()).isEqualTo(401);
        var student=login("20260001");
        var response=student.http.send(student.builder("/api/assistant/certificate/prepare").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input("")))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        for(String role:List.of("teacher1001","admin","counselor1001","affairs1001")) assertThat(prepare(login(role),input("实习核验")).statusCode()).isEqualTo(403);
        verify(model,never()).prepareCertificate(anyString());
    }
    @Test void emptyPurposeAsksForInputAndReadsOnlyCurrentRegisteredIdentityWithoutWriting() throws Exception {
        long before=count(); var result=ok(prepare(login("20260001"),input("")));
        assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.path("missing").toString()).contains("purpose");
        assertThat(result.path("identity").path("identityNumber").asText()).isEqualTo("20260001"); assertThat(result.path("source").path("version").asInt()).isEqualTo(1);
        assertThat(result.has("identityConfirmed")).isFalse(); assertThat(count()).isEqualTo(before); verify(model,never()).prepareCertificate(anyString());
    }
    @Test void supplementsAreMergedLocallyAndOnlyCurrentTextGoesToTheModel() throws Exception {
        online(Map.of()); var body=input("实习单位核验在校身份"); body.put("mode","DEEPSEEK"); body.put("externalConsent",true); body.put("message","保持原用途");
        long before=count(); var result=ok(prepare(login("20260001"),body));
        assertThat(result.path("ready").asBoolean()).isTrue(); assertThat(result.path("fields").path("purpose").asText()).isEqualTo("实习单位核验在校身份");
        verify(model).prepareCertificate("保持原用途"); assertThat(count()).isEqualTo(before);
        var audit=jdbc.sql("SELECT * FROM agent_run WHERE id=:id").param("id",result.path("runId").asText()).query().singleRow();
        assertThat(audit.toString()).contains("collect_certificate_fields").doesNotContain("实习单位","保持原用途","20260001");
    }
    @Test void explicitPurposeExtractionCanUpdateAPreviewButCannotConfirmIdentity() throws Exception {
        online(Map.of("purpose","实习核验在校身份")); var body=input(""); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
        var result=ok(prepare(login("20260001"),body)); assertThat(result.path("fields").path("purpose").asText()).isEqualTo("实习核验在校身份"); assertThat(result.has("identityConfirmed")).isFalse();
    }
    @Test void modelCannotSetIdentityConfirmationDeliveryStatusOrCommands() throws Exception {
        var student=login("20260001"); long before=count();
        for(var patch:List.of(Map.of("identityConfirmed",true),Map.of("studentId",2),Map.of("identityNumber","other"),Map.of("deliveryMethod","OFFLINE"),Map.of("status","COMPLETED"),Map.of("purpose",true),Map.of("purpose"," "),Map.of("purpose","x".repeat(301)))) {
            online(patch); var body=input("实习核验"); body.put("mode","DEEPSEEK"); body.put("externalConsent",true);
            var result=prepare(student,body); assertThat(result.statusCode()).as(result.body()).isEqualTo(502); assertThat(result.body()).contains("MODEL_CERTIFICATE_FIELDS_REJECTED");
        }
        doReturn(new Plan(List.of(new Call("submit_certificate",json.valueToTree(Map.of()))),1,1)).when(model).prepareCertificate(anyString());
        var body=input("实习核验"); body.put("mode","DEEPSEEK"); body.put("externalConsent",true); assertThat(prepare(student,body).statusCode()).isEqualTo(502); assertThat(count()).isEqualTo(before);
    }
    @Test void consentConfigurationAndFieldLimitsAreCheckedBeforeModelUse() throws Exception {
        var student=login("20260001"); var body=input(""); body.put("mode","DEEPSEEK");
        assertThat(prepare(student,body).statusCode()).isEqualTo(400); body.put("externalConsent",true); assertThat(prepare(student,body).statusCode()).isEqualTo(503);
        body.put("fields",Map.of("purpose","x".repeat(301))); assertThat(prepare(student,body).statusCode()).isEqualTo(400); verify(model,never()).prepareCertificate(anyString());
    }
    @Test void withdrawnGuideAndIncompleteIdentityBlockReadiness() throws Exception {
        var student=login("20260001"); jdbc.sql("UPDATE agent_guide SET status='WITHDRAWN' WHERE slug='certificate'").update();
        try { var result=ok(prepare(student,input("实习核验"))); assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.path("source").isNull()).isTrue(); }
        finally { jdbc.sql("UPDATE agent_guide SET status='PUBLISHED' WHERE slug='certificate'").update(); }
        Long group=jdbc.sql("SELECT class_group_id FROM app_user WHERE username='20260001'").query(Long.class).single(); jdbc.sql("UPDATE app_user SET class_group_id=NULL WHERE username='20260001'").update();
        try { var result=ok(prepare(student,input("实习核验"))); assertThat(result.path("ready").asBoolean()).isFalse(); assertThat(result.path("identity").isNull()).isTrue(); }
        finally { jdbc.sql("UPDATE app_user SET class_group_id=:g WHERE username='20260001'").param("g",group).update(); }
    }
    @Test void absenceOfAnAffairsReviewerAllowsDraftButPreventsSubmission() throws Exception {
        jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE username='affairs1001'").update();
        try { var result=ok(prepare(login("20260001"),input("实习核验"))); assertThat(result.path("ready").asBoolean()).isTrue(); assertThat(result.path("canSubmit").asBoolean()).isFalse(); assertThat(result.path("eligibilityMessage").asText()).contains("暂不能提交"); }
        finally { jdbc.sql("UPDATE app_user SET enabled=TRUE WHERE username='affairs1001'").update(); }
    }
    @Test void identityChangeAfterPreviewIsRejectedByTheOrdinarySave() throws Exception {
        var student=login("20260001"); var preview=ok(prepare(student,input("实习核验"))); var body=draft(preview); long before=count();
        String original=jdbc.sql("SELECT full_name FROM app_user WHERE username='20260001'").query(String.class).single();
        jdbc.sql("UPDATE app_user SET full_name='已更新的演示学生' WHERE username='20260001'").update();
        try { var result=student.send("POST","/api/certificates/mine",body); assertThat(result.statusCode()).isEqualTo(409); assertThat(result.body()).contains("登记资料已变化"); assertThat(count()).isEqualTo(before); }
        finally { jdbc.sql("UPDATE app_user SET full_name=:n WHERE username='20260001'").param("n",original).update(); }
    }
    @Test void onlyPersonalConfirmationCreatesOneOwnedDraftAndNoNotifications() throws Exception {
        var student=login("20260001"); var preview=ok(prepare(student,input("实习核验"))); var body=draft(preview); long before=count(),notices=jdbc.sql("SELECT COUNT(*) FROM user_notice").query(Long.class).single();
        body.put("identityConfirmed",false); assertThat(student.send("POST","/api/certificates/mine",body).statusCode()).isEqualTo(400); assertThat(count()).isEqualTo(before);
        body.put("identityConfirmed",true); var saved=ok(student.send("POST","/api/certificates/mine",body)); var retry=ok(student.send("POST","/api/certificates/mine",body));
        assertThat(saved.path("status").asText()).isEqualTo("DRAFT"); assertThat(saved.path("version").asInt()).isZero(); assertThat(retry.path("id").asLong()).isEqualTo(saved.path("id").asLong());
        assertThat(count()).isEqualTo(before+1); assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice").query(Long.class).single()).isEqualTo(notices);
        assertThat(login("20260002").get("/api/certificates/mine/"+saved.path("id").asLong()).statusCode()).isEqualTo(404);
    }
    @Test void originalSubmissionReviewAndOwnedResultRemainAvailableAfterAssistantPreparation() throws Exception {
        var student=login("20260001"); var saved=ok(student.send("POST","/api/certificates/mine",draft(ok(prepare(student,input("实习核验")))))); long id=saved.path("id").asLong();
        var submitted=ok(student.send("POST","/api/certificates/mine/"+id+"/submit",Map.of("version",0,"comment","本人确认后提交")));
        var completed=ok(login("affairs1001").send("POST","/api/certificates/review/"+id+"/decision",Map.of("version",submitted.path("version").asInt(),"action","APPROVE","comment","演示用途核验完成")));
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        var answer=ok(student.send("POST","/api/assistant/query",Map.of("message","这条证明的处理状态","mode","LOCAL","externalConsent",false,"context",Map.of("type","CERTIFICATE_MINE","id",id))));
        assertThat(answer.toString()).contains("办理完成","/portal/certificates?id="+id).doesNotContain("实习核验");
        var result=student.http.send(student.builder("/api/certificates/mine/"+id+"/result").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(result.statusCode()).isEqualTo(200); assertThat(result.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf"); assertThat(result.body()).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(login("20260002").get("/api/certificates/mine/"+id+"/result").statusCode()).isEqualTo(404);
    }
    @Test void sessionInvalidationDuringModelExtractionBlocksPreviewDelivery() throws Exception {
        var student=login("20260002"); doReturn(true).when(model).ready();
        doAnswer(i->{ jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE username='20260002'").update(); return new Plan(List.of(new Call("collect_certificate_fields",json.valueToTree(Map.of("purpose","实习核验")))),1,1); }).when(model).prepareCertificate(anyString());
        var body=input(""); body.put("mode","DEEPSEEK"); body.put("externalConsent",true); long before=count(); assertThat(prepare(student,body).statusCode()).isEqualTo(401); assertThat(count()).isEqualTo(before);
    }
    private long count() { return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single(); }
    private void online(Map<String,?> patch) { doReturn(true).when(model).ready(); doReturn(new Plan(List.of(new Call("collect_certificate_fields",json.valueToTree(patch))),30,5)).when(model).prepareCertificate(anyString()); }
    private HashMap<String,Object> input(String purpose) { return new HashMap<>(Map.of("message","准备证明","mode","LOCAL","externalConsent",false,"fields",Map.of("purpose",purpose))); }
    private HashMap<String,Object> draft(JsonNode preview) { return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"purpose",preview.path("fields").path("purpose").asText(),"deliveryMethod","DIGITAL","identityConfirmed",true,"version",0,"expectedIdentity",json.convertValue(preview.path("identity"),Map.class))); }
    private HttpResponse<String> prepare(Browser student,Map<String,?> body) throws Exception { return student.send("POST","/api/assistant/certificate/prepare",body); }
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
