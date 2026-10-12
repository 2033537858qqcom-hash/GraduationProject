package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:campus-leave-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class LeaveIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    final String root="/api/leave/";
    @Test void permissionsAndResponsibilityAreSeparateFromTeachingAndAdministration() throws Exception {
        assertThat(new Browser().get(root+"mine").statusCode()).isEqualTo(401);
        for(String username:List.of("admin","teacher1001","academic1001","editor","staff1001")) {
            var other=login(username); assertThat(other.get(root+"mine").statusCode()).isEqualTo(403); assertThat(other.get(root+"review").statusCode()).isEqualTo(403);
        }
        assertThat(login("20260001").get("/api/admin/counselors").statusCode()).isEqualTo(403);
        assertThat(login("counselor1001").get(root+"review").statusCode()).isEqualTo(200);
        assertThat(login("admin").get("/api/admin/counselors").statusCode()).isEqualTo(200);
    }
    @Test void draftCreationRetriesDoNotCreateDuplicateApplications() throws Exception {
        var f=fixture(); var input=input("2031-01-02"); var student=login(f.student());
        var first=ok(student.send("POST",root+"mine",input)); var retry=ok(student.send("POST",root+"mine",input));
        assertThat(id(first)).isEqualTo(id(retry)); assertThat(student.tree(root+"mine/"+id(first)+"/history").path("total").asInt()).isEqualTo(1);
        input.put("reason","不同内容"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(409);
    }
    @Test void invalidFieldsTimesContactsAndUnknownStatesAreRejected() throws Exception {
        var f=fixture(); var student=login(f.student());
        var input=input("2032-01-02"); input.put("endsAt","2032-01-02T08:00:00"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        input=input("2032-01-02"); input.put("endsAt","2032-03-02T11:00:00"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        input=input("2032-01-02"); input.put("contact","不是手机或邮箱"); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        input=input("2032-01-02"); input.put("reason","   "); assertThat(student.send("POST",root+"mine",input).statusCode()).isEqualTo(400);
        assertThat(student.get(root+"mine?status=UNKNOWN").statusCode()).isEqualTo(400);
        assertThat(student.get(root+"mine?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(student.get(root+"mine/calendar?from=2032-01-01&to=2034-01-01").statusCode()).isEqualTo(400);
    }
    @Test void missingAssignmentAllowsDraftButBlocksSubmissionAndForgedReviewerIsIgnored() throws Exception {
        var f=fixture(); var admin=login("admin"); ok(admin.send("PUT","/api/admin/counselors/"+f.group(),Map.of("counselorId",0,"version",1,"reason","无效候选")),400);
        var unset=new HashMap<String,Object>(); unset.put("counselorId",null); unset.put("version",1); unset.put("reason","未配置演示"); ok(admin.send("PUT","/api/admin/counselors/"+f.group(),unset));
        var student=login(f.student()); var input=input("2033-01-02"); input.put("reviewerId",user("admin")); var draft=ok(student.send("POST",root+"mine",input));
        assertThat(student.tree(root+"mine/eligibility").path("canSubmit").asBoolean()).isFalse(); assertThat(submit(student,draft).statusCode()).isEqualTo(409);
        assertThat(student.tree(root+"mine/"+id(draft)).path("status").asText()).isEqualTo("DRAFT");
    }
    @Test void studentAndReviewerCannotReadForeignOrUnsubmittedApplications() throws Exception {
        var first=fixture(); var second=fixture(); var student=login(first.student()); var draft=create(student,"2034-01-02");
        assertThat(login(second.student()).get(root+"mine/"+id(draft)).statusCode()).isEqualTo(404);
        assertThat(login(first.counselor()).get(root+"review/"+id(draft)).statusCode()).isEqualTo(404);
        var request=ok(submit(student,draft));
        assertThat(login(second.counselor()).get(root+"review/"+id(request)).statusCode()).isEqualTo(404);
        assertThat(login(second.counselor()).get(root+"review/"+id(request)+"/history").statusCode()).isEqualTo(404);
        assertThat(login(first.counselor()).tree(root+"review?status=SUBMITTED").path("total").asInt()).isEqualTo(1);
    }
    @Test void returnedApplicationsRetainPreviousContentAndResubmitToCurrentReviewer() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.counselor()); var draft=create(student,"2035-01-02"); var submitted=ok(submit(student,draft));
        var returned=ok(decide(reviewer,submitted,"RETURN","请补充事由说明")); assertThat(returned.path("status").asText()).isEqualTo("RETURNED");
        var input=input("2035-01-02"); input.put("requestKey",returned.path("requestKey").asText()); input.put("version",returned.path("version").asInt()); input.put("reason","补充后的申请事由");
        var edited=ok(student.send("PUT",root+"mine/"+id(returned),input)); var resubmitted=ok(submit(student,edited));
        assertThat(edited.path("processingComment").asText()).isEqualTo("请补充事由说明");
        assertThat(resubmitted.path("status").asText()).isEqualTo("SUBMITTED"); var history=student.tree(root+"mine/"+id(draft)+"/history?pageSize=2");
        assertThat(history.path("total").asInt()).isEqualTo(5); assertThat(history.path("items").size()).isEqualTo(2);
        assertThat(student.tree(root+"mine/"+id(draft)+"/history?page=3&pageSize=2").path("items").get(0).path("reason").asText()).isEqualTo("演示申请事由");
    }
    @Test void approvalProducesOneCalendarEntryAndNoticeWithoutPrivateReason() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.counselor()); var request=ok(submit(student,create(student,"2036-01-02")));
        long before=jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE user_id=:u").param("u",user(f.student())).query(Long.class).single();
        var approved=ok(decide(reviewer,request,"APPROVE","已核对，同意申请")); assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(decide(reviewer,request,"APPROVE","重复点击").statusCode()).isEqualTo(409);
        assertThat(student.tree(root+"mine/calendar?from=2036-01-01&to=2036-02-01").size()).isEqualTo(1);
        assertThat(login("20260001").tree(root+"mine/calendar?from=2036-01-01&to=2036-02-01").size()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE user_id=:u").param("u",user(f.student())).query(Long.class).single()).isEqualTo(before+1);
        assertThat(jdbc.sql("SELECT body FROM user_notice WHERE user_id=:u ORDER BY id DESC LIMIT 1").param("u",user(f.student())).query(String.class).single()).doesNotContain("演示申请事由","example.test");
        assertThat(student.send("POST",root+"mine/"+id(approved)+"/withdraw",action(approved)).statusCode()).isEqualTo(409);
    }
    @Test void overlappingActiveRequestsAreBlockedButAdjacentAndWithdrawnRequestsAreAllowed() throws Exception {
        var f=fixture(); var student=login(f.student()); var first=ok(submit(student,create(student,"2037-01-02"))); var second=create(student,"2037-01-02");
        assertThat(submit(student,second).statusCode()).isEqualTo(409);
        var adjacent=input("2037-01-02"); adjacent.put("startsAt","2037-01-02T11:00:00"); adjacent.put("endsAt","2037-01-02T12:00:00"); var third=ok(student.send("POST",root+"mine",adjacent)); ok(submit(student,third));
        ok(student.send("POST",root+"mine/"+id(first)+"/withdraw",action(first))); ok(submit(student,second));
    }
    @Test void withdrawnAndRejectedRecordsAreImmutableAndClosePendingWork() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.counselor()); var submitted=ok(submit(student,create(student,"2038-01-02")));
        var rejected=ok(decide(reviewer,submitted,"REJECT","信息未满足演示办理条件"));
        var input=input("2038-01-02"); input.put("requestKey",rejected.path("requestKey").asText()); input.put("version",rejected.path("version").asInt()); assertThat(student.send("PUT",root+"mine/"+id(rejected),input).statusCode()).isEqualTo(409);
        var another=ok(submit(student,create(student,"2038-01-02"))); ok(student.send("POST",root+"mine/"+id(another)+"/withdraw",action(another)));
        assertThat(reviewer.tree(root+"review?status=SUBMITTED").path("total").asInt()).isZero(); assertThat(jdbc.sql("SELECT COUNT(*) FROM leave_calendar WHERE student_id=:u").param("u",user(f.student())).query(Long.class).single()).isZero();
    }
    @Test void reassignmentImmediatelyRevokesOldScopeAndPreventsOrphaningPendingWork() throws Exception {
        var f=fixture(); var student=login(f.student()); var old=login(f.counselor()); var submitted=ok(submit(student,create(student,"2039-01-02"))); var admin=login("admin");
        var unset=new HashMap<String,Object>(); unset.put("counselorId",null); unset.put("version",1); unset.put("reason","不能丢弃待办"); assertThat(admin.send("PUT","/api/admin/counselors/"+f.group(),unset).statusCode()).isEqualTo(409);
        String replacement=person(false,f.group()); ok(admin.send("PUT","/api/admin/counselors/"+f.group(),Map.of("counselorId",user(replacement),"version",1,"reason","移交演示待办")));
        assertThat(old.get(root+"review/"+id(submitted)).statusCode()).isEqualTo(404); assertThat(decide(old,submitted,"APPROVE","无权处理").statusCode()).isEqualTo(404);
        assertThat(login(replacement).tree(root+"review?status=SUBMITTED").path("total").asInt()).isEqualTo(1); ok(decide(login(replacement),submitted,"APPROVE","移交后核对"));
    }
    @Test void assignedCounselorAndPendingStudentCannotBeMovedOrDisabledIntoOrphanedState() throws Exception {
        var f=fixture(); var admin=login("admin"); var counselor=admin.tree("/api/admin/foundation/users?q="+f.counselor()).path("items").get(0);
        assertThat(admin.send("POST","/api/admin/foundation/users/"+user(f.counselor())+"/state",Map.of("enabled",false,"version",counselor.path("version").asInt(),"reason","先移交")).statusCode()).isEqualTo(409);
        var student=login(f.student()); ok(submit(student,create(student,"2040-01-02"))); var profile=admin.tree("/api/admin/foundation/users?q="+f.student()).path("items").get(0);
        var change=new HashMap<String,Object>(); change.put("fullName",profile.path("fullName").asText()); change.put("identityNumber",profile.path("identityNumber").asText()); change.put("campusId",1); change.put("departmentId",1); change.put("classGroupId",1); change.put("roles",List.of("STUDENT")); change.put("version",profile.path("version").asInt()); change.put("reason","调整班级");
        assertThat(admin.send("PUT","/api/admin/foundation/users/"+user(f.student()),change).statusCode()).isEqualTo(409);
    }
    @Test void staleVersionsAndPastSubmissionProduceNoPartialHistory() throws Exception {
        var f=fixture(); var student=login(f.student()); var draft=create(student,"2041-01-02"); var request=ok(submit(student,draft));
        assertThat(submit(student,draft).statusCode()).isEqualTo(409); assertThat(student.tree(root+"mine/"+id(request)+"/history").path("total").asInt()).isEqualTo(2);
        var past=create(student,"2020-01-02"); assertThat(submit(student,past).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE leave_request SET starts_at='2020-01-02 08:00:00',ends_at='2020-01-02 11:00:00' WHERE id=:id").param("id",id(request)).update();
        assertThat(decide(login(f.counselor()),request,"APPROVE","过期结果").statusCode()).isEqualTo(409);
    }
    @Test void privateFilesKeepOldVersionsAndCheckOwnershipOnEveryDownload() throws Exception {
        var f=fixture(); var other=fixture(); var student=login(f.student()); var draft=create(student,"2042-01-02");
        var withFile=ok(student.upload(id(draft),0,"note.txt","演示材料第一版")); long oldFile=withFile.path("fileId").asLong();
        assertThat(login(f.counselor()).get(root+"files/"+oldFile+"?view=review").statusCode()).isEqualTo(404);
        var submitted=ok(submit(student,withFile)); var reviewer=login(f.counselor()); assertThat(reviewer.get(root+"files/"+oldFile+"?view=review").body()).isEqualTo("演示材料第一版");
        assertThat(login(other.student()).get(root+"files/"+oldFile+"?view=mine").statusCode()).isEqualTo(404); assertThat(login("admin").get(root+"files/"+oldFile+"?view=review").statusCode()).isEqualTo(403);
        var returned=ok(decide(reviewer,submitted,"RETURN","请补交材料")); var revised=ok(student.upload(id(returned),returned.path("version").asInt(),"note.txt","演示材料第二版"));
        assertThat(revised.path("fileId").asLong()).isNotEqualTo(oldFile); assertThat(student.get(root+"files/"+oldFile+"?view=mine").body()).isEqualTo("演示材料第一版");
        assertThat(student.upload(id(revised),revised.path("version").asInt(),"fake.png","非图片内容").statusCode()).isEqualTo(400);
        String next=person(false,f.group()); ok(login("admin").send("PUT","/api/admin/counselors/"+f.group(),Map.of("counselorId",user(next),"version",1,"reason","移交材料访问")));
        assertThat(reviewer.get(root+"files/"+oldFile+"?view=review").statusCode()).isEqualTo(404); assertThat(login(next).get(root+"files/"+oldFile+"?view=review").statusCode()).isEqualTo(200);
    }
    @Test void simultaneousApproveAndWithdrawalCommitExactlyOneResult() throws Exception {
        var f=fixture(); var student=login(f.student()); var reviewer=login(f.counselor()); var request=ok(submit(student,create(student,"2043-01-02")));
        var one=CompletableFuture.supplyAsync(()->unchecked(()->decide(reviewer,request,"APPROVE","并发审核").statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->student.send("POST",root+"mine/"+id(request)+"/withdraw",action(request)).statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
        var current=student.tree(root+"mine/"+id(request)); long events=jdbc.sql("SELECT COUNT(*) FROM leave_calendar WHERE request_id=:id").param("id",id(request)).query(Long.class).single();
        assertThat(events).isEqualTo(current.path("status").asText().equals("APPROVED")?1:0); assertThat(student.tree(root+"mine/"+id(request)+"/history").path("total").asInt()).isEqualTo(3);
    }
    @Test void simultaneousSubmissionsReserveOnlyOneOverlappingInterval() throws Exception {
        var f=fixture(); var first=login(f.student()); var second=login(f.student()); var a=create(first,"2044-01-02"); var b=create(second,"2044-01-02");
        var one=CompletableFuture.supplyAsync(()->unchecked(()->submit(first,a).statusCode())); var two=CompletableFuture.supplyAsync(()->unchecked(()->submit(second,b).statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409); assertThat(first.tree(root+"mine?status=SUBMITTED").path("total").asInt()).isEqualTo(1);
    }
    @Test void nobodyCanSubmitToOrReceiveTheirOwnPendingApplication() throws Exception {
        var f=fixture(); long studentId=user(f.student());
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'TEACHER'),(:id,'COUNSELOR')").param("id",studentId).update();
        var student=login(f.student()); var submitted=ok(submit(student,create(student,"2045-01-02")));
        var admin=login("admin"); assertThat(admin.send("PUT","/api/admin/counselors/"+f.group(),Map.of("counselorId",studentId,"version",1,"reason","拒绝自审移交")).statusCode()).isEqualTo(409);
        ok(student.send("POST",root+"mine/"+id(submitted)+"/withdraw",action(submitted)));
        ok(admin.send("PUT","/api/admin/counselors/"+f.group(),Map.of("counselorId",studentId,"version",1,"reason","无待办时配置演示")));
        var own=create(student,"2045-01-03"); assertThat(submit(student,own).statusCode()).isEqualTo(409); assertThat(student.get(root+"review/"+id(own)).statusCode()).isEqualTo(404);
    }
    @Test void counselorRequiresTeacherIdentityAndSameAcademyResponsibility() throws Exception {
        var admin=login("admin"); var fields=new HashMap<String,Object>(); String key="counselor-"+UUID.randomUUID();
        fields.put("username",key); fields.put("fullName","岗位验证演示"); fields.put("identityNumber",key); fields.put("campusId",1); fields.put("departmentId",2); fields.put("roles",List.of("COUNSELOR")); fields.put("initialPassword","Test@202610");
        assertThat(ok(admin.send("POST","/api/admin/foundation/users",fields),400).path("code").asText()).isEqualTo("INVALID_ASSIGNMENT");
        fields.put("roles",List.of("COUNSELOR","TEACHER")); var created=ok(admin.send("POST","/api/admin/foundation/users",fields)); var f=fixture();
        assertThat(admin.send("PUT","/api/admin/counselors/"+f.group(),Map.of("counselorId",id(created),"version",1,"reason","拒绝跨学院负责")).statusCode()).isEqualTo(400);
    }
    private record Fixture(long group,String student,String counselor) {}
    private Fixture fixture() throws Exception {
        String key=UUID.randomUUID().toString(); jdbc.sql("INSERT INTO class_group(major_id,name) VALUES(1,:n)").param("n","请假测试班-"+key).update(); long group=jdbc.sql("SELECT id FROM class_group WHERE name=:n").param("n","请假测试班-"+key).query(Long.class).single();
        String student=person(true,group), counselor=person(false,group); ok(login("admin").send("PUT","/api/admin/counselors/"+group,Map.of("counselorId",user(counselor),"version",0,"reason","隔离测试负责关系"))); return new Fixture(group,student,counselor);
    }
    private String person(boolean student,long group) {
        String name=(student?"ls-":"lc-")+UUID.randomUUID(); jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id) SELECT :n,password_hash,:n,:n,1,1,:g FROM app_user WHERE username='20260001'").param("n",name).param("g",student?group:null).update();
        long id=user(name); jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",id).param("role",student?"STUDENT":"TEACHER").update(); if(!student) jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'COUNSELOR')").param("id",id).update(); return name;
    }
    private Map<String,Object> input(String day) { var input=new HashMap<String,Object>(); input.put("requestKey",UUID.randomUUID().toString()); input.put("reasonType","PERSONAL"); input.put("reason","演示申请事由"); input.put("contact","demo-student@example.test"); input.put("startsAt",day+"T08:00:00"); input.put("endsAt",day+"T11:00:00"); input.put("version",0); return input; }
    private JsonNode create(Browser student,String day) throws Exception { return ok(student.send("POST",root+"mine",input(day))); }
    private Map<String,Object> action(JsonNode request) { return Map.of("version",request.path("version").asInt(),"comment","演示流程操作说明"); }
    private HttpResponse<String> submit(Browser student,JsonNode request) throws Exception { return student.send("POST",root+"mine/"+id(request)+"/submit",action(request)); }
    private HttpResponse<String> decide(Browser reviewer,JsonNode request,String action,String comment) throws Exception { return reviewer.send("POST",root+"review/"+id(request)+"/decision",Map.of("version",request.path("version").asInt(),"action",action,"comment",comment)); }
    private long user(String name) { return jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single(); }
    private long id(JsonNode request) { return request.path("id").asLong(); }
    private JsonNode ok(HttpResponse<String> response) throws Exception { return ok(response,200); }
    private JsonNode ok(HttpResponse<String> response,int code) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(code); return json.readTree(response.body()); }
    private Browser login(String name) throws Exception { var b=new Browser(); b.csrf(); ok(b.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026"))); b.csrf(); return b; }
    @FunctionalInterface interface Operation { int run() throws Exception; }
    private int unchecked(Operation operation) { try { return operation.run(); } catch(Exception ex) { throw new RuntimeException(ex); } }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String header,token;
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        JsonNode tree(String path) throws Exception { return json.readTree(get(path).body()); }
        void csrf() throws Exception { var data=tree("/api/auth/csrf"); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> upload(long id,int version,String name,String text) throws Exception {
            String boundary="leave-"+UUID.randomUUID(); String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: text/plain\r\n\r\n"+text+"\r\n--"+boundary+"--\r\n";
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+root+"mine/"+id+"/file?version="+version)).header(header,token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
