package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-certificate-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class CertificateIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean cn.zzuit.campus.certificate.CertificatePdf pdf;
    final JsonMapper json=JsonMapper.builder().build();
    final String root="/api/certificates/";
    @Test void unrelatedRolesAndAnonymousCannotReadOrProcessProofs() throws Exception {
        assertThat(new Browser().get(root+"mine").statusCode()).isEqualTo(401);
        for(String name:List.of("teacher1001","admin","counselor1001","staff1001","academic1001","editor")) {
            var actor=login(name); assertThat(actor.get(root+"mine").statusCode()).isEqualTo(403); assertThat(actor.get(root+"review").statusCode()).isEqualTo(403);
        }
        assertThat(login("20260001").get(root+"review").statusCode()).isEqualTo(403);
        assertThat(login("affairs1001").get(root+"review").statusCode()).isEqualTo(200);
    }
    @Test void fieldsIdentityConfirmationAndDigitalDeliveryAreValidated() throws Exception {
        var f=fixture(); var student=login(f.student());
        for(Map<String,Object> input:List.of(input(""),input("x".repeat(301)))) assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        var input=input("演示用途"); input.put("identityConfirmed",false); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        input=input("演示用途"); input.remove("identityConfirmed"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        input=input("演示用途"); input.put("deliveryMethod","PAPER"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        input=input("演示用途"); input.put("requestKey","short"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        var request=create(student,"服务端登记信息"); assertThat(request.path("identity").path("studentName").asText()).isEqualTo("演示学生");
        assertThat(request.path("identity").path("departmentId").asLong()).isEqualTo(f.department());
    }
    @Test void idempotentDraftRetriesPreserveOnlyOneInitialRevision() throws Exception {
        var f=fixture(); var student=login(f.student()); var input=input("首次草稿重试");
        var request=ok(student.send("POST",root+"mine",input)); var retry=ok(student.send("POST",root+"mine",input)); assertThat(id(request)).isEqualTo(id(retry));
        assertThat(student.tree(root+"mine/"+id(request)+"/history").path("total").asInt()).isEqualTo(1);
        input.put("purpose","不同内容"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(409);
        input.put("requestKey",request.path("requestKey").asText()); input.put("purpose","更新内容"); input.put("version",0);
        ok(student.send("PUT",root+"mine/"+id(request),input)); input.put("purpose","首次草稿重试"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(409);
    }
    @Test void draftAndSubmittedRecordsStayWithinOwnerAndAcademyScope() throws Exception {
        var f=fixture(); var other=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer()); var draft=create(student,"私有用途");
        assertThat(reviewer.get(root+"review/"+id(draft)).statusCode()).isEqualTo(404);
        var submitted=ok(submit(student,draft));
        assertThat(login(other.student()).get(root+"mine/"+id(submitted)).statusCode()).isEqualTo(404);
        assertThat(login(other.reviewer()).get(root+"review/"+id(submitted)).statusCode()).isEqualTo(404);
        assertThat(login(other.reviewer()).get(root+"review/"+id(submitted)+"/history").statusCode()).isEqualTo(404);
        assertThat(reviewer.tree(root+"review").path("total").asInt()).isEqualTo(1);
    }
    @Test void returnResubmitAndCompletionKeepHistoryAndProducePrivateChinesePdf() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer());
        var submitted=ok(submit(student,create(student,"原演示用途"))); var returned=ok(decide(reviewer,submitted,"RETURN","请补充用途"));
        var input=input("补交后的演示用途"); input.put("requestKey",returned.path("requestKey").asText()); input.put("version",2);
        var edited=ok(student.send("PUT",root+"mine/"+id(returned),input)); assertThat(edited.path("processingComment").asText()).isEqualTo("请补充用途");
        var again=ok(submit(student,edited)); var completed=ok(decide(reviewer,again,"APPROVE","登记信息已核对")); assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        var history=student.tree(root+"mine/"+id(completed)+"/history"); assertThat(history.path("total").asInt()).isEqualTo(6);
        assertThat(history.path("items").get(4).path("purpose").asText()).isEqualTo("原演示用途");
        var file=student.bytes(root+"mine/"+id(completed)+"/result"); assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(file.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        assertThat(file.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        try(var document=Loader.loadPDF(file.body())) {
            assertThat(new PDFTextStripper().getText(document)).contains("毕业设计演示","非学校正式证明","演示学生","补交后的演示用途");
            assertThat(document.getNumberOfPages()).isEqualTo(1); assertThat(document.getDocumentCatalog().getOpenAction()).isNull();
        }
        assertThat(reviewer.bytes(root+"review/"+id(completed)+"/result").statusCode()).isEqualTo(200);
        assertThat(login("admin").bytes(root+"review/"+id(completed)+"/result").statusCode()).isEqualTo(403);
        var other=fixture(); assertThat(login(other.student()).bytes(root+"mine/"+id(completed)+"/result").statusCode()).isEqualTo(404);
        assertThat(decide(reviewer,completed,"APPROVE","不能重复生成").statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_result WHERE request_id=:id").param("id",id(completed)).query(Long.class).single()).isEqualTo(1);
    }
    @Test void noReviewerAllowsDraftButNotSubmissionAndIncompleteIdentityCannotCreate() throws Exception {
        var f=fixture(); jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE username=:n").param("n",f.reviewer()).update(); var student=login(f.student());
        assertThat(student.tree(root+"mine/eligibility").path("canSubmit").asBoolean()).isFalse();
        assertThat(submit(student,create(student,"无处理人草稿")).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE app_user SET class_group_id=NULL WHERE username=:n").param("n",f.student()).update();
        assertThat(student.get(root+"mine/eligibility").statusCode()).isEqualTo(409);
        assertThat(student.send("POST",root+"mine",input("不完整身份")).statusCode()).isEqualTo(409);
    }
    @Test void staleTransitionsAndClosedRecordsAreImmutable() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer()); var draft=create(student,"过时版本验证");
        var input=input("新版本"); input.put("requestKey",draft.path("requestKey").asText()); var edited=ok(student.send("PUT",root+"mine/"+id(draft),input));
        assertThat(submit(student,draft).statusCode()).isEqualTo(409); var submitted=ok(submit(student,edited)); var rejected=ok(decide(reviewer,submitted,"REJECT","演示拒绝"));
        assertThat(submit(student,rejected).statusCode()).isEqualTo(409); assertThat(student.send("POST",root+"mine/"+id(rejected)+"/withdraw",action(rejected)).statusCode()).isEqualTo(409);
        input.put("version",rejected.path("version").asInt()); assertThat(student.send("PUT",root+"mine/"+id(rejected),input).statusCode()).isEqualTo(409);
        assertThat(student.bytes(root+"mine/"+id(rejected)+"/result").statusCode()).isEqualTo(404);
    }
    @Test void changedIdentityRequiresReconfirmationBeforeSubmissionAndApproval() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer()); var draft=create(student,"资料变化验证");
        jdbc.sql("UPDATE app_user SET full_name='修改后的演示学生' WHERE username=:n").param("n",f.student()).update();
        assertThat(submit(student,draft).statusCode()).isEqualTo(409);
        var input=input("资料变化验证"); input.put("requestKey",draft.path("requestKey").asText()); var updated=ok(student.send("PUT",root+"mine/"+id(draft),input)); var submitted=ok(submit(student,updated));
        jdbc.sql("UPDATE app_user SET identity_number='NEW-DEMO-NUMBER' WHERE username=:n").param("n",f.student()).update();
        assertThat(decide(reviewer,submitted,"APPROVE","不应生成旧资料证明").statusCode()).isEqualTo(409);
        assertThat(student.tree(root+"mine/"+id(draft)).path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_result WHERE request_id=:id").param("id",id(draft)).query(Long.class).single()).isZero();
        ok(decide(reviewer,submitted,"RETURN","重新确认学号"));
    }
    @Test void ownProofCannotBeSubmittedToOrReviewedByOneself() throws Exception {
        var f=fixture(); jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,'STUDENT_AFFAIRS' FROM app_user WHERE username=:n").param("n",f.student()).update();
        jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE username=:n").param("n",f.reviewer()).update(); var student=login(f.student()); var draft=create(student,"禁止自审"); assertThat(submit(student,draft).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE app_user SET enabled=TRUE WHERE username=:n").param("n",f.reviewer()).update(); var submitted=ok(submit(student,draft));
        assertThat(student.get(root+"review/"+id(submitted)).statusCode()).isEqualTo(404); assertThat(decide(student,submitted,"APPROVE","自审").statusCode()).isEqualTo(404);
    }
    @Test void approvalAndWithdrawalRaceHaveOneWinner() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer()); var submitted=ok(submit(student,create(student,"并发撤回")));
        var one=CompletableFuture.supplyAsync(()->unchecked(()->decide(reviewer,submitted,"APPROVE","并发核对").statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->student.send("POST",root+"mine/"+id(submitted)+"/withdraw",action(submitted)).statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
        var state=student.tree(root+"mine/"+id(submitted)).path("status").asText();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_result WHERE request_id=:id").param("id",id(submitted)).query(Long.class).single()).isEqualTo(state.equals("COMPLETED")?1:0);
    }
    @Test void twoReviewersCannotGenerateTwoResults() throws Exception {
        var f=fixture(); var student=login(f.student()); var first=login(f.reviewer()); var second=login(person(f.department(),f.group(),false)); var submitted=ok(submit(student,create(student,"并发生成")));
        var one=CompletableFuture.supplyAsync(()->unchecked(()->decide(first,submitted,"APPROVE","人员一核对").statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->decide(second,submitted,"APPROVE","人员二核对").statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_result WHERE request_id=:id").param("id",id(submitted)).query(Long.class).single()).isEqualTo(1);
    }
    @Test void pendingApplicationsProtectStudentRelationsAndLastReviewer() throws Exception {
        var f=fixture(); var student=login(f.student()); var admin=login("admin"); var submitted=ok(submit(student,create(student,"关系保护")));
        long reviewerId=user(f.reviewer()); assertThat(admin.send("POST","/api/admin/foundation/users/"+reviewerId+"/state",Map.of("enabled",false,"version",0,"reason","禁止遗留无处理人待办")).statusCode()).isEqualTo(409);
        var reviewerFields=account(f.reviewer(),f.department(),null,List.of("SERVICE_STAFF"));
        assertThat(admin.send("PUT","/api/admin/foundation/users/"+reviewerId,reviewerFields).statusCode()).isEqualTo(409);
        long otherGroup=jdbc.sql("SELECT id FROM class_group WHERE id<>:g ORDER BY id LIMIT 1").param("g",f.group()).query(Long.class).single();
        var fields=account(f.student(),f.department(),null,List.of("SERVICE_STAFF"));
        assertThat(admin.send("PUT","/api/admin/foundation/users/"+user(f.student()),fields).statusCode()).isEqualTo(409);
        String replacement=person(f.department(),otherGroup,false); ok(admin.send("PUT","/api/admin/foundation/users/"+reviewerId,reviewerFields));
        assertThat(login(f.reviewer()).get(root+"review/"+id(submitted)).statusCode()).isEqualTo(403);
        assertThat(login(replacement).get(root+"review/"+id(submitted)).statusCode()).isEqualTo(200);
    }
    @Test void resultBytesRemainStableAfterLaterProfileChanges() throws Exception {
        var f=fixture(); var student=login(f.student()); var completed=ok(decide(login(f.reviewer()),ok(submit(student,create(student,"结果快照"))),"APPROVE","核对演示资料"));
        var original=student.bytes(root+"mine/"+id(completed)+"/result").body(); jdbc.sql("UPDATE app_user SET full_name='之后的新名称' WHERE username=:n").param("n",f.student()).update();
        assertThat(student.bytes(root+"mine/"+id(completed)+"/result").body()).isEqualTo(original);
        assertThat(student.tree(root+"mine/"+id(completed)).path("identity").path("studentName").asText()).isEqualTo("演示学生");
    }
    @Test void unifiedAffairsAreOwnedPagedAndDoNotCopyPrivateFields() throws Exception {
        var f=fixture(); var student=login(f.student()); create(student,"私有用途不进入汇总");
        ok(student.send("POST","/api/leave/mine",Map.of("requestKey",UUID.randomUUID().toString(),"reasonType","PERSONAL","reason","私有请假事由","contact","private@example.test","startsAt","2035-01-02T08:00:00","endsAt","2035-01-02T11:00:00","version",0)));
        var all=student.tree("/api/affairs/mine?pageSize=1"); assertThat(all.path("total").asInt()).isEqualTo(2); assertThat(all.path("summary").path("drafts").asInt()).isEqualTo(2); assertThat(all.path("items").size()).isEqualTo(1);
        assertThat(all.toString()).doesNotContain("私有用途","私有请假事由","private@example.test");
        assertThat(student.tree("/api/affairs/mine?kind=CERTIFICATE").path("total").asInt()).isEqualTo(1);
        var other=fixture(); assertThat(login(other.student()).tree("/api/affairs/mine").path("total").asInt()).isZero();
        assertThat(student.get("/api/affairs/mine?kind=UNKNOWN").statusCode()).isEqualTo(400); assertThat(student.get("/api/affairs/mine?page=0").statusCode()).isEqualTo(400);
    }
    @Test void fullMessagePaginationReadCountsAndOwnershipWorkBeyondTwentyRows() throws Exception {
        var f=fixture(); long owner=user(f.student());
        for(int i=0;i<45;i++) jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:u,:t,'普通演示消息')").param("u",owner).param("t","分页演示-"+i).update();
        var student=login(f.student()); var third=student.tree("/api/notices?page=3&pageSize=20"); assertThat(third.path("total").asInt()).isEqualTo(45); assertThat(third.path("items").size()).isEqualTo(5); assertThat(third.path("unreadCount").asInt()).isEqualTo(45);
        long notice=third.path("items").get(0).path("id").asLong(); ok(student.send("POST","/api/notices/"+notice+"/read",Map.of())); ok(student.send("POST","/api/notices/"+notice+"/read",Map.of()));
        assertThat(student.tree("/api/notices?read=true").path("total").asInt()).isEqualTo(1); assertThat(student.tree("/api/notices?read=false").path("unreadCount").asInt()).isEqualTo(44);
        var other=fixture(); assertThat(login(other.student()).send("POST","/api/notices/"+notice+"/read",Map.of()).statusCode()).isEqualTo(404);
        assertThat(student.get("/api/notices?pageSize=101").statusCode()).isEqualTo(400); assertThat(student.get("/api/notices?read=invalid").statusCode()).isEqualTo(400);
    }
    @Test void messageLinksRecheckCurrentScopeAndNeverExposePurpose() throws Exception {
        var f=fixture(); var other=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer()); var submitted=ok(submit(student,create(student,"秘密演示用途")));
        var notices=student.tree("/api/notices"); assertThat(notices.toString()).doesNotContain("秘密演示用途"); assertThat(notices.path("items").get(0).path("targetPath").asText()).isEqualTo("/portal/certificates?id="+id(submitted)+"&tab=details");
        assertThat(reviewer.tree("/api/notices").path("items").get(0).path("targetPath").asText()).contains("/student-affairs/certificates?id=");
        person(f.department(),f.group(),false);
        var fields=account(f.reviewer(),other.department(),null,List.of("STUDENT_AFFAIRS")); ok(login("admin").send("PUT","/api/admin/foundation/users/"+user(f.reviewer()),fields));
        assertThat(reviewer.get(root+"review/"+id(submitted)).statusCode()).isEqualTo(401);
        var moved=login(f.reviewer()); assertThat(moved.tree("/api/notices").path("items").get(0).path("targetPath").isNull()).isTrue(); assertThat(moved.get(root+"review/"+id(submitted)).statusCode()).isEqualTo(404);
    }
    @Test void leaveMessagesAlsoResolveAndLoseTheirLinkAfterCounselorTransfer() throws Exception {
        var f=fixture(); long old=user(f.reviewer()); jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'TEACHER'),(:u,'COUNSELOR')").param("u",old).update();
        jdbc.sql("INSERT INTO counselor_assignment(class_group_id,counselor_id) VALUES(:g,:u)").param("g",f.group()).param("u",old).update(); var student=login(f.student());
        var draft=ok(student.send("POST","/api/leave/mine",Map.of("requestKey",UUID.randomUUID().toString(),"reasonType","PERSONAL","reason","保密事由","contact","demo@example.test","startsAt","2036-01-02T08:00:00","endsAt","2036-01-02T11:00:00","version",0)));
        ok(student.send("POST","/api/leave/mine/"+id(draft)+"/submit",action(draft))); var reviewer=login(f.reviewer());
        assertThat(student.tree("/api/notices").path("items").get(0).path("targetPath").asText()).startsWith("/portal/leave?id=");
        assertThat(reviewer.tree("/api/notices").path("items").get(0).path("targetPath").asText()).startsWith("/student-affairs/leave?id=");
        long replacement=user(person(f.department(),f.group(),false)); jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'TEACHER'),(:u,'COUNSELOR')").param("u",replacement).update();
        jdbc.sql("UPDATE counselor_assignment SET counselor_id=:u WHERE class_group_id=:g").param("u",replacement).param("g",f.group()).update();
        assertThat(reviewer.tree("/api/notices").path("items").get(0).path("targetPath").isNull()).isTrue();
    }
    @Test void longChinesePurposeAndUnsupportedCharactersDoNotBreakPdf() throws Exception {
        var f=fixture(); var student=login(f.student()); String purpose="长文本演示："+"核对用途与信息。".repeat(32)+"🧭";
        var completed=ok(decide(login(f.reviewer()),ok(submit(student,create(student,purpose))),"APPROVE","核对长文本"));
        try(var document=Loader.loadPDF(student.bytes(root+"mine/"+id(completed)+"/result").body())) { assertThat(new PDFTextStripper().getText(document)).contains("长文本演示","非学校正式证明"); assertThat(document.getNumberOfPages()).isBetween(1,3); }
    }
    @Test void generationFailureRollsBackStatusHistoryResultAndNotices() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.reviewer()); var submitted=ok(submit(student,create(student,"生成异常回滚")));
        long requestId=id(submitted); long before=student.tree("/api/notices").path("total").asLong();
        doThrow(new java.io.IOException("contained rendering failure")).when(pdf).generate(argThat(request->request.id()==requestId),anyString(),any(java.time.LocalDateTime.class),anyString());
        assertThat(decide(reviewer,submitted,"APPROVE","失败不应关闭待办").statusCode()).isEqualTo(503);
        assertThat(student.tree(root+"mine/"+requestId).path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(student.tree(root+"mine/"+requestId+"/history").path("total").asInt()).isEqualTo(2);
        assertThat(student.tree("/api/notices").path("total").asLong()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificate_result WHERE request_id=:id").param("id",requestId).query(Long.class).single()).isZero();
    }
    private record Fixture(long department,long group,String student,String reviewer) {}
    private Fixture fixture() {
        String key="证明测试-"+UUID.randomUUID(); jdbc.sql("INSERT INTO department(name,kind) VALUES(:n,'COLLEGE')").param("n",key).update(); long department=jdbc.sql("SELECT id FROM department WHERE name=:n").param("n",key).query(Long.class).single();
        jdbc.sql("INSERT INTO major(department_id,name) VALUES(:d,'演示专业')").param("d",department).update(); long major=jdbc.sql("SELECT id FROM major WHERE department_id=:d").param("d",department).query(Long.class).single();
        jdbc.sql("INSERT INTO class_group(major_id,name) VALUES(:m,'演示行政班')").param("m",major).update(); long group=jdbc.sql("SELECT id FROM class_group WHERE major_id=:m").param("m",major).query(Long.class).single();
        return new Fixture(department,group,person(department,group,true),person(department,group,false));
    }
    private String person(long department,long group,boolean student) {
        String name="proof-"+UUID.randomUUID(); jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id) SELECT :n,password_hash,:full,:n,1,:d,:g FROM app_user WHERE username='20260001'")
            .param("n",name).param("full",student?"演示学生":"演示办理人员").param("d",department).param("g",student?group:null).update();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,:r FROM app_user WHERE username=:n").param("r",student?"STUDENT":"STUDENT_AFFAIRS").param("n",name).update(); return name;
    }
    private Map<String,Object> account(String username,long department,Long group,List<String> roles) { var result=new HashMap<String,Object>(); result.put("fullName","演示人员"); result.put("identityNumber",username); result.put("campusId",1); result.put("departmentId",department); result.put("classGroupId",group); result.put("roles",roles); result.put("version",0); result.put("reason","演示关系验证"); return result; }
    private Map<String,Object> input(String purpose) { var result=new HashMap<String,Object>(); result.put("requestKey",UUID.randomUUID().toString()); result.put("purpose",purpose); result.put("deliveryMethod","DIGITAL"); result.put("identityConfirmed",true); result.put("version",0); return result; }
    private JsonNode create(Browser student,String purpose) throws Exception { return ok(student.send("POST",root+"mine",input(purpose))); }
    private Map<String,Object> action(JsonNode request) { return Map.of("version",request.path("version").asInt(),"comment","演示核对与操作"); }
    private HttpResponse<String> submit(Browser student,JsonNode request) throws Exception { return student.send("POST",root+"mine/"+id(request)+"/submit",action(request)); }
    private HttpResponse<String> decide(Browser reviewer,JsonNode request,String action,String comment) throws Exception { return reviewer.send("POST",root+"review/"+id(request)+"/decision",Map.of("version",request.path("version").asInt(),"action",action,"comment",comment)); }
    private long user(String name) { return jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single(); }
    private long id(JsonNode request) { return request.path("id").asLong(); }
    private JsonNode ok(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body()); }
    private Browser login(String name) throws Exception { var browser=new Browser(); browser.csrf(); ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026"))); browser.csrf(); return browser; }
    @FunctionalInterface interface Operation { int run() throws Exception; }
    private int unchecked(Operation operation) { try { return operation.run(); } catch(Exception ex) { throw new RuntimeException(ex); } }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String header,token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<byte[]> bytes(String path) throws Exception { return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofByteArray()); }
        JsonNode tree(String path) throws Exception { return json.readTree(get(path).body()); }
        void csrf() throws Exception { var data=tree("/api/auth/csrf"); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception { return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
