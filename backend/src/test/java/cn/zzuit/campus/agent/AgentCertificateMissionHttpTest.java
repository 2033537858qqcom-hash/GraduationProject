package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:certificate-mission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false",
    "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentCertificateMissionHttpTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean DeepSeekClient model;
    @BeforeEach void offline(){doReturn(false).when(model).ready();}
    Map<String,Object> fields(){return Map.of("purpose","仅本地保存的实习单位身份核验用途");}
    long count(){return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single();}
    long session(Browser user)throws Exception{return ok(user.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
    String path(long session){return "/api/assistant/sessions/"+session+"/missions";}
    Map<String,Object> input(Map<String,Object> fields){
        var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备在校证明草稿","mode","LOCAL","externalConsent",false));
        if(fields!=null)body.put("certificateFields",fields);return body;
    }
    Map<String,Object> command(JsonNode task,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",task.path("version").asInt(),"action",action));}
    Map<String,Object> confirmation(JsonNode task){var body=command(task,"CONFIRM");body.put("confirmed",true);body.put("proposalHash",task.path("proposalHash").asText());return body;}
    JsonNode act(Browser user,long session,JsonNode task,Map<String,Object> body)throws Exception{return ok(user.post(path(session)+"/"+task.path("id").asLong()+"/actions",body));}

    @Test void confirmedCertificateSavesSnapshotAndVerifiesWithoutSubmitting()throws Exception{
        var user=login("20260001");long s=session(user),before=count();var body=input(fields());var task=ok(user.post(path(s),body));
        assertThat(task.path("kind").asText()).isEqualTo("CERTIFICATE");
        assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(task.path("steps").toString()).contains("check_certificate_requirements","inspect_certificate_identity","prepare_certificate");
        assertThat(task.path("certificatePreview").path("identity").path("studentName").asText()).isNotBlank();
        assertThat(count()).isEqualTo(before);
        assertThat(ok(user.post(path(s),body)).path("id").asLong()).isEqualTo(task.path("id").asLong());
        var confirm=confirmation(task);task=act(user,s,task,confirm);
        assertThat(task.path("stage").asText()).isEqualTo("DONE");assertThat(task.path("business").path("status").asText()).isEqualTo("DRAFT");
        long id=task.path("business").path("id").asLong();
        assertThat(task.path("business").path("targetPath").asText()).isEqualTo("/portal/certificates?id="+id+"&tab=details");
        var original=ok(user.get("/api/certificates/mine/"+id));
        assertThat(original.path("purpose").asText()).isEqualTo(fields().get("purpose"));
        assertThat(original.path("identity")).isEqualTo(task.path("certificatePreview").path("identity"));
        assertThat(original.path("identityConfirmed").asBoolean()).isTrue();assertThat(original.path("documentNumber").isNull()).isTrue();
        assertThat(act(user,s,task,confirm).path("business").path("id").asLong()).isEqualTo(id);assertThat(count()).isEqualTo(before+1);
        assertThat(ok(user.get(path(s)+"/"+task.path("id").asLong())).path("stage").asText()).isEqualTo("DONE");
    }
    @Test void missingPurposeCanBeRevisedAndMustBePersonallyConfirmed()throws Exception{
        var user=login("20260002");long s=session(user),before=count();var task=ok(user.post(path(s),input(null)));
        assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("missing").toString()).contains("purpose");
        var revise=command(task,"REVISE");revise.put("certificateFields",fields());task=act(user,s,task,revise);
        assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
        var confirm=confirmation(task);confirm.put("confirmed",false);
        assertThat(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",confirm).statusCode()).isEqualTo(400);assertThat(count()).isEqualTo(before);
    }
    @Test void changedPurposeInvalidatesOldConfirmation()throws Exception{
        var user=login("20260001");long s=session(user);var task=ok(user.post(path(s),input(fields())));String old=task.path("proposalHash").asText();
        var revise=command(task,"REVISE");revise.put("certificateFields",Map.of("purpose","新的实际用途"));task=act(user,s,task,revise);
        assertThat(task.path("proposalHash").asText()).isNotEqualTo(old);var confirm=confirmation(task);confirm.put("proposalHash",old);
        assertThat(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",confirm).statusCode()).isEqualTo(409);
    }
    @Test void changedIdentityRequiresFreshPreviewAndSnapshot()throws Exception{
        var user=login("20260001");long s=session(user),before=count();var task=ok(user.post(path(s),input(fields())));
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE username='20260001'").query(String.class).single();
        jdbc.sql("UPDATE app_user SET full_name='已更新的登记姓名' WHERE username='20260001'").update();
        try{
            task=act(user,s,task,confirmation(task));assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");
            assertThat(task.path("proposalHash").isNull()).isTrue();assertThat(count()).isEqualTo(before);
            var revise=command(task,"REVISE");revise.put("certificateFields",fields());task=act(user,s,task,revise);
            assertThat(task.path("certificatePreview").path("identity").path("studentName").asText()).isEqualTo("已更新的登记姓名");
            assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
        }finally{jdbc.sql("UPDATE app_user SET full_name=:name WHERE username='20260001'").param("name",name).update();}
    }
    @Test void changedOrWithdrawnGuideCannotReuseConfirmation()throws Exception{
        var user=login("20260001");long s=session(user),before=count();var task=ok(user.post(path(s),input(fields())));
        jdbc.sql("UPDATE agent_guide SET version=version+1 WHERE slug='certificate'").update();
        try{task=act(user,s,task,confirmation(task));assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(count()).isEqualTo(before);}
        finally{jdbc.sql("UPDATE agent_guide SET version=version-1 WHERE slug='certificate'").update();}
    }
    @Test void incompleteRegistrationBlocksPreview()throws Exception{
        var user=login("20260002");long s=session(user);long group=jdbc.sql("SELECT class_group_id FROM app_user WHERE username='20260002'").query(Long.class).single();
        jdbc.sql("UPDATE app_user SET class_group_id=NULL WHERE username='20260002'").update();
        try{var task=ok(user.post(path(s),input(fields())));assertThat(task.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(task.path("certificatePreview").path("issues").toString()).contains("登记资料");}
        finally{jdbc.sql("UPDATE app_user SET class_group_id=:g WHERE username='20260002'").param("g",group).update();}
    }
    @Test void unavailableReviewerStillAllowsDraftWithAccurateNotice()throws Exception{
        var user=login("20260001");long s=session(user);jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE username='affairs1001'").update();
        try{var task=ok(user.post(path(s),input(fields())));assertThat(task.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");assertThat(task.path("certificatePreview").path("canSubmit").asBoolean()).isFalse();assertThat(task.path("certificatePreview").path("eligibilityMessage").asText()).contains("暂不能提交");task=act(user,s,task,confirmation(task));assertThat(task.path("stage").asText()).isEqualTo("DONE");}
        finally{jdbc.sql("UPDATE app_user SET enabled=TRUE WHERE username='affairs1001'").update();}
    }
    @Test void wrongRoleForeignSessionAndMixedTargetsAreRejected()throws Exception{
        var teacher=login("teacher1001");assertThat(teacher.post(path(session(teacher)),input(fields())).statusCode()).isEqualTo(403);
        var user=login("20260001");long s=session(user);var task=ok(user.post(path(s),input(fields())));
        assertThat(login("20260002").get(path(s)+"/"+task.path("id").asLong()).statusCode()).isEqualTo(404);
        for(String goal:List.of("请假并办理在校证明","预约教室后开在校证明","报修并准备在校证明")){var body=input(fields());body.put("goal",goal);assertThat(user.post(path(s),body).statusCode()).isEqualTo(400);}
        var manual=input(fields());manual.put("goal","准备我的申请");manual.put("kind","CERTIFICATE");assertThat(ok(user.post(path(s),manual)).path("kind").asText()).isEqualTo("CERTIFICATE");
    }
    @Test void modelObservesOnlyMinimizedStatusIncludingAfterSave()throws Exception{
        doReturn(true).when(model).ready();
        doAnswer(invocation->{List<String> allowed=invocation.getArgument(1);String observations=JSON.writeValueAsString(invocation.getArgument(2));
            assertThat(observations).doesNotContain("实习单位","studentName","identityNumber","departmentId","classGroupId","purpose\":");
            assertThat(allowed).doesNotContain("save_leave","save_repair","find_venues");return new Plan(List.of(new Call(allowed.getFirst(),JSON.valueToTree(Map.of()))),10,2);
        }).when(model).nextMissionStep(anyString(),anyList(),anyList());
        var user=login("20260001");long s=session(user);var body=input(fields());body.put("mode","DEEPSEEK");body.put("externalConsent",true);
        var task=ok(user.post(path(s),body));task=act(user,s,task,confirmation(task));assertThat(task.path("stage").asText()).isEqualTo("DONE");
    }
    @Test void wrongDomainModelActionFailsWithoutWriting()throws Exception{
        doReturn(true).when(model).ready();doReturn(new Plan(List.of(new Call("save_leave",JSON.valueToTree(Map.of()))),10,2)).when(model).nextMissionStep(anyString(),anyList(),anyList());
        var user=login("20260001");var body=input(fields());body.put("mode","DEEPSEEK");body.put("externalConsent",true);long before=count();
        assertThat(ok(user.post(path(session(user)),body)).path("stage").asText()).isEqualTo("FAILED");assertThat(count()).isEqualTo(before);
    }
    @Test void changedOriginalIsPreservedWhenVerificationResumes()throws Exception{
        doReturn(true).when(model).ready();
        doAnswer(invocation->{List<String> allowed=invocation.getArgument(1);String action=allowed.getFirst();return new Plan(List.of(new Call(action.equals("verify_certificate")?"save_leave":action,JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());
        var user=login("20260001");long s=session(user);var body=input(fields());body.put("mode","DEEPSEEK");body.put("externalConsent",true);
        var task=ok(user.post(path(s),body));task=act(user,s,task,confirmation(task));assertThat(task.path("stage").asText()).isEqualTo("FAILED");long id=task.path("business").path("id").asLong();
        jdbc.sql("UPDATE certificate_request SET purpose='原页面已编辑',version=version+1 WHERE id=:id").param("id",id).update();
        doAnswer(invocation->{List<String> allowed=invocation.getArgument(1);return new Plan(List.of(new Call(allowed.getFirst(),JSON.valueToTree(Map.of()))),10,2);}).when(model).nextMissionStep(anyString(),anyList(),anyList());
        task=act(user,s,task,command(task,"CONTINUE"));assertThat(task.path("stage").asText()).isEqualTo("FAILED");assertThat(task.path("message").asText()).contains("MISSION_RESULT_CHANGED");
        assertThat(jdbc.sql("SELECT purpose FROM certificate_request WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("原页面已编辑");
    }
    @Test void cancelledTaskCannotCreateDraft()throws Exception{
        var user=login("20260001");long s=session(user),before=count();var task=ok(user.post(path(s),input(fields())));task=act(user,s,task,command(task,"CANCEL"));
        assertThat(task.path("stage").asText()).isEqualTo("CANCELLED");assertThat(user.post(path(s)+"/"+task.path("id").asLong()+"/actions",confirmation(task)).statusCode()).isEqualTo(409);assertThat(count()).isEqualTo(before);
    }
    JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return JSON.readTree(response.body());}
    Browser login(String username)throws Exception{var browser=new Browser();browser.csrf();ok(browser.post("/api/auth/login",Map.of("username",username,"password","Demo@2026")));browser.csrf();return browser;}
    class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=JSON.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> post(String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
