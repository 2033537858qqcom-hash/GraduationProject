package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
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
    "spring.datasource.url=jdbc:h2:mem:campus-repair-workflow-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class RepairWorkflowIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/repairs";
    @Test void authenticationCsrfAndPerViewPermissionsAreRequired() throws Exception {
        assertThat(new Browser().get(ROOT+"/mine").statusCode()).isEqualTo(401);
        for(String name:List.of("admin","dispatcher1001","technician1001","staff1001"))assertThat(login(name).get(ROOT+"/mine").statusCode()).isEqualTo(403);
        var student=login("20260001");assertThat(student.get(ROOT+"/dispatch").statusCode()).isEqualTo(403);
        var request=student.builder(ROOT+"/mine").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(draft(null)))).build();
        assertThat(student.http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    @Test void studentAndTeacherCanDraftButOtherPeopleCannotReadPrivateFields() throws Exception {
        for(String name:List.of("20260001","teacher1001")){
            var owner=login(name);var r=ok(owner.send("POST",ROOT+"/mine",draft(place(1))));long id=r.path("id").asLong();
            assertThat(r.path("status").asText()).isEqualTo("DRAFT");
            assertThat(ok(owner.get(ROOT+"/mine")).toString()).doesNotContain("contact","description","snapshot","private-demo@example.test");
            for(String other:List.of("20260002","admin","dispatcher1001","technician1001"))assertThat(login(other).get(ROOT+"/"+(other.equals("dispatcher1001")?"dispatch":other.equals("technician1001")?"work":"mine")+"/"+id).statusCode()).isIn(403,404);
        }
        assertThat(login("teacher1001").get("/api/affairs/mine").statusCode()).isEqualTo(403);
    }
    @Test void partialDraftCanBeRestoredButEmptyOrInvalidFieldsAreRejected() throws Exception {
        var s=login("20260001");var d=draft(null);d.put("contact","");d.put("category","");d.put("description","");
        var r=ok(s.send("POST",ROOT+"/mine",d));assertThat(ok(s.get(ROOT+"/mine/"+r.path("id").asLong())).path("title").asText()).isEqualTo("演示灯具故障");
        d=draft(null);d.put("contact","invalid");assertThat(s.send("POST",ROOT+"/mine",d).statusCode()).isEqualTo(400);
        d=new HashMap<>(Map.of("requestKey",key(),"version",0));assertThat(s.send("POST",ROOT+"/mine",d).statusCode()).isEqualTo(400);
        assertThat(s.get(ROOT+"/mine?pageSize=101").statusCode()).isEqualTo(400);
    }
    @Test void submissionRequiresCompleteFieldsAvailableSourceAndCurrentDispatcher() throws Exception {
        var s=login("20260001");long west=place(2);var d=draft(west);var r=ok(s.send("POST",ROOT+"/mine",d));
        assertThat(sendAction(s,"mine",r,"submit",null).statusCode()).isEqualTo(409);
        assertThat(ok(s.get(ROOT+"/mine/"+r.path("id").asLong())).path("status").asText()).isEqualTo("DRAFT");
        d=draft(null);r=ok(s.send("POST",ROOT+"/mine",d));assertThat(sendAction(s,"mine",r,"submit",null).statusCode()).isEqualTo(400);
        long location=place(1);r=ok(s.send("POST",ROOT+"/mine",draft(location)));jdbc.sql("UPDATE repair_location SET enabled=FALSE WHERE id=:id").param("id",location).update();
        assertThat(sendAction(s,"mine",r,"submit",null).statusCode()).isEqualTo(400);
    }
    @Test void fullWorkflowNeedsReporterConfirmationAndOnlyOneEvaluation() throws Exception {
        var flow=flow("20260001");var r=assign(flow);r=act(flow.worker(),"work",r,"accept",null);r=act(flow.worker(),"work",r,"complete",null);
        assertThat(r.path("status").asText()).isEqualTo("AWAITING_CONFIRMATION");assertThat(r.path("confirmedAt").isNull()).isTrue();
        assertThat(sendAction(flow.dispatcher(),"dispatch",r,"confirm",Map.of("resultRound",1)).statusCode()).isEqualTo(409);
        r=act(flow.owner(),"mine",r,"confirm",Map.of("resultRound",1));assertThat(r.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(r.path("confirmedAt").isNull()).isFalse();r=act(flow.owner(),"mine",r,"evaluation",Map.of("stars",5,"resultRound",1,"comment","已解决"));
        assertThat(r.path("evaluation").path("stars").asInt()).isEqualTo(5);
        assertThat(sendAction(flow.owner(),"mine",r,"evaluation",Map.of("stars",3,"resultRound",1)).statusCode()).isEqualTo(409);
        assertThat(sendAction(flow.worker(),"work",r,"complete",null).statusCode()).isEqualTo(409);
    }
    @Test void returnResubmitKeepsSubmittedLocationAndHistory() throws Exception {
        var f=flow("teacher1001");var r=act(f.dispatcher(),"dispatch",f.request(),"return",Map.of("comment","补充故障现象"));
        var d=draft(f.location());d.put("version",r.path("version").asInt());d.put("description","已补充，灯具持续闪烁");r=ok(f.owner().send("PUT",ROOT+"/mine/"+r.path("id").asLong(),d));
        var invalid=new HashMap<>(d);invalid.put("requestKey",key());invalid.put("version",r.path("version").asInt());invalid.put("category","NETWORK");
        assertThat(f.owner().send("PUT",ROOT+"/mine/"+r.path("id").asLong(),invalid).statusCode()).isEqualTo(409);
        r=act(f.owner(),"mine",r,"submit",null);var history=ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong()+"/history"));
        assertThat(history.toString()).contains("补充故障现象","灯不亮","持续闪烁");assertThat(history.path("total").asLong()).isEqualTo(5);
    }
    @Test void reassignRevokesOldWorkerIncludingImagesAndOldNoticeLinks() throws Exception {
        var f=flow("20260001");var r=assign(f);r=act(f.worker(),"work",r,"accept",null);r=ok(f.worker().upload(r,"work","RESULT",png(),key()));long image=r.path("images").get(0).path("id").asLong();
        long newWorker=otherWorker(f.location());r=act(f.dispatcher(),"dispatch",r,"reassign",Map.of("technicianId",newWorker));
        long id=r.path("id").asLong();assertThat(f.worker().get(ROOT+"/work/"+id).statusCode()).isEqualTo(404);
        assertThat(f.worker().get(ROOT+"/work/"+id+"/attachments/"+image).statusCode()).isEqualTo(404);
        assertThat(f.worker().get(ROOT+"/work/"+id+"/history").statusCode()).isEqualTo(404);
        var notices=ok(f.worker().get("/api/notices?pageSize=100"));
        for(var notice:notices.path("items"))if(notice.path("body").asText().startsWith("申请 #"+id+" ·"))assertThat(notice.path("targetPath").isNull()).isTrue();
    }
    @Test void currentScopeRevocationBlocksDetailHistoryDownloadAndPendingCount() throws Exception {
        var f=flow("20260001");var r=assign(f);long worker=user("technician1001");
        // Restrict only this location, leaving independent tests' default scopes intact.
        jdbc.sql("UPDATE repair_request SET category='NETWORK' WHERE id=:id").param("id",r.path("id").asLong()).update();
        jdbc.sql("UPDATE repair_staff_scope SET enabled=FALSE WHERE user_id=:u AND category='NETWORK'").param("u",worker).update();
        try {assertThat(f.worker().get(ROOT+"/work/"+r.path("id").asLong()).statusCode()).isEqualTo(404);assertThat(ok(f.worker().get(ROOT+"/work?status=ASSIGNED")).path("items").toString()).doesNotContain("\"id\":"+r.path("id").asLong()+",");}
        finally {jdbc.sql("UPDATE repair_staff_scope SET enabled=TRUE WHERE user_id=:u AND category='NETWORK'").param("u",worker).update();}
    }
    @Test void confirmationRoundsAreCheckedAndPreviousResultsAreKept() throws Exception {
        var f=flow("20260001");var r=assign(f);r=act(f.worker(),"work",r,"accept",null);r=act(f.worker(),"work",r,"complete",Map.of("comment","第一次处理"));
        assertThat(sendAction(f.owner(),"mine",r,"confirm",Map.of("resultRound",0)).statusCode()).isEqualTo(409);
        r=act(f.owner(),"mine",r,"reopen",Map.of("resultRound",1,"comment","问题仍未解决"));r=act(f.worker(),"work",r,"complete",Map.of("comment","第二次处理"));
        assertThat(r.path("completions").size()).isEqualTo(2);assertThat(r.path("completionRound").asInt()).isEqualTo(2);
        assertThat(sendAction(f.owner(),"mine",r,"confirm",Map.of("resultRound",1)).statusCode()).isEqualTo(409);
        assertThat(act(f.owner(),"mine",r,"confirm",Map.of("resultRound",2)).path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void repeatCreateAndTransitionAreIdempotentButDifferentContentConflicts() throws Exception {
        var s=login("20260001");var d=draft(place(1));var r=ok(s.send("POST",ROOT+"/mine",d));var body=action(r,null);
        var submitted=ok(s.send("POST",ROOT+"/mine/"+r.path("id").asLong()+"/submit",body));
        long actions=actionCount(r),notices=noticeCount(r);
        assertThat(ok(s.send("POST",ROOT+"/mine",d)).path("id").asLong()).isEqualTo(r.path("id").asLong());
        assertThat(ok(s.send("POST",ROOT+"/mine/"+r.path("id").asLong()+"/submit",body)).path("version").asInt()).isEqualTo(submitted.path("version").asInt());
        assertThat(actionCount(r)).isEqualTo(actions);assertThat(noticeCount(r)).isEqualTo(notices);
        body.put("comment","不同内容");assertThat(s.send("POST",ROOT+"/mine/"+r.path("id").asLong()+"/submit",body).statusCode()).isEqualTo(409);
    }
    @Test void concurrentAssignmentsHaveOneWinnerAndOneAssignment() throws Exception {
        var f=flow("20260001");var a=action(f.request(),Map.of("technicianId",user("technician1001")));var b=action(f.request(),Map.of("technicianId",user("technician1001")));
        var first=CompletableFuture.supplyAsync(()->unchecked(f.dispatcher(),f.request(),a));var second=CompletableFuture.supplyAsync(()->unchecked(f.dispatcher(),f.request(),b));
        assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM repair_assignment WHERE request_id=:id").param("id",f.request().path("id").asLong()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void invalidImageContentAndCrossRequestAccessAreRejected() throws Exception {
        var f=flow("20260001");assertThat(f.owner().upload(f.request(),"mine","FAULT",png(),key()).statusCode()).isEqualTo(409); // Submitted records are immutable.
        var r=ok(f.owner().send("POST",ROOT+"/mine",draft(f.location())));assertThat(f.owner().upload(r,"mine","FAULT","<svg/>".getBytes(StandardCharsets.UTF_8),key()).statusCode()).isEqualTo(400);
        r=ok(f.owner().upload(r,"mine","FAULT",png(),key()));long image=r.path("images").get(0).path("id").asLong();
        var downloaded=f.owner().http.send(f.owner().builder(ROOT+"/mine/"+r.path("id").asLong()+"/attachments/"+image).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.body()).isEqualTo(png());assertThat(downloaded.headers().firstValue("Cache-Control")).contains("no-store");assertThat(downloaded.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(login("20260002").get(ROOT+"/mine/"+r.path("id").asLong()+"/attachments/"+image).statusCode()).isEqualTo(404);
        assertThat(f.owner().get(ROOT+"/mine/"+f.request().path("id").asLong()+"/attachments/"+image).statusCode()).isEqualTo(404);
    }
    @Test void removedResultImageStaysInHistoryButIsNotPartOfCompletion() throws Exception {
        var f=flow("20260001");var r=assign(f);r=act(f.worker(),"work",r,"accept",null);r=ok(f.worker().upload(r,"work","RESULT",png(),key()));long image=r.path("images").get(0).path("id").asLong();
        r=ok(f.worker().send("POST",ROOT+"/work/"+r.path("id").asLong()+"/attachments/"+image+"/remove",action(r,null)));r=act(f.worker(),"work",r,"complete",null);
        assertThat(r.path("completions").get(0).path("images").size()).isZero();assertThat(f.owner().get(ROOT+"/mine/"+r.path("id").asLong()+"/attachments/"+image).statusCode()).isEqualTo(200);
        assertThat(ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong()+"/history")).toString()).contains("UPLOAD","REMOVE_IMAGE");
    }
    @Test void imageLimitAndDuplicateUploadDoNotCreateExtraAttachments() throws Exception {
        var s=login("20260001");var r=ok(s.send("POST",ROOT+"/mine",draft(place(1))));String key=key();var first=r;r=ok(s.upload(r,"mine","FAULT",png(),key));
        assertThat(ok(s.upload(first,"mine","FAULT",png(),key)).path("images").size()).isEqualTo(1);
        for(int i=1;i<6;i++)r=ok(s.upload(r,"mine","FAULT",png(),key()));
        assertThat(s.upload(r,"mine","FAULT",png(),key()).statusCode()).isEqualTo(400);assertThat(r.path("images").size()).isEqualTo(6);
    }
    @Test void transactionFailureRollsBackStateHistoryAndNotices() throws Exception {
        var s=login("20260001");var r=ok(s.send("POST",ROOT+"/mine",draft(place(1))));long id=r.path("id").asLong();
        jdbc.sql("ALTER TABLE user_notice ADD CONSTRAINT repair_notice_failure CHECK(source_type <> 'REPAIR' OR source_id <> "+id+")").update();
        try {assertThat(sendAction(s,"mine",r,"submit",null).statusCode()).isEqualTo(409);assertThat(ok(s.get(ROOT+"/mine/"+id)).path("version").asInt()).isZero();assertThat(actionCount(r)).isEqualTo(1);assertThat(noticeCount(r)).isZero();}
        finally {jdbc.sql("ALTER TABLE user_notice DROP CONSTRAINT repair_notice_failure").update();}
    }
    @Test void unableReturnsToDispatcherAndWithdrawRejectStopFurtherActions() throws Exception {
        var f=flow("20260001");var r=assign(f);r=act(f.worker(),"work",r,"accept",null);r=act(f.worker(),"work",r,"unable",Map.of("comment","需要其他专业人员"));
        assertThat(r.path("status").asText()).isEqualTo("SUBMITTED");assertThat(r.path("technicianId").isNull()).isTrue();assertThat(f.worker().get(ROOT+"/work/"+r.path("id").asLong()).statusCode()).isEqualTo(404);
        r=act(f.dispatcher(),"dispatch",r,"reject",null);assertThat(sendAction(f.owner(),"mine",r,"submit",null).statusCode()).isEqualTo(409);
        var second=flow("teacher1001");r=act(second.owner(),"mine",second.request(),"withdraw",null);assertThat(sendAction(second.dispatcher(),"dispatch",r,"assign",Map.of("technicianId",user("technician1001"))).statusCode()).isEqualTo(409);
    }
    @Test void unifiedAffairsAndMessagesUseRealAuthorizedRepairLinks() throws Exception {
        var f=flow("20260001");long id=f.request().path("id").asLong();
        assertThat(ok(f.owner().get("/api/affairs/mine?kind=REPAIR")).toString()).contains("校园报修","/repairs/mine?id="+id+"&tab=details").doesNotContain("private-demo@example.test");
        assertThat(ok(f.owner().get("/api/notices?pageSize=100")).toString()).contains("/repairs/mine?id="+id).doesNotContain("private-demo@example.test","灯不亮");
        assertThat(ok(f.dispatcher().get(ROOT+"/summary")).path("dispatchPending").asLong()).isGreaterThan(0);
    }
    @Test void cannotAssignReporterOrSomeoneOutsideCurrentCapabilityAndScope() throws Exception {
        var f=flow("20260001");assertThat(sendAction(f.dispatcher(),"dispatch",f.request(),"assign",Map.of("technicianId",user("20260001"))).statusCode()).isEqualTo(400);
        assertThat(sendAction(f.dispatcher(),"dispatch",f.request(),"assign",Map.of("technicianId",user("teacher1001"))).statusCode()).isEqualTo(400);
        assertThat(sendAction(f.worker(),"work",f.request(),"accept",null).statusCode()).isEqualTo(404);
        var assigned=assign(f);assertThat(sendAction(f.dispatcher(),"dispatch",assigned,"reassign",Map.of("technicianId",user("technician1001"))).statusCode()).isEqualTo(409);
    }
    private String key(){return UUID.randomUUID().toString();}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private long place(long campus)throws Exception {return ok(login("admin").send("POST","/api/admin/repair-settings/locations",Map.of("campusId",campus,"code",key(),"name","流程测试地点","kind","CAMPUS_PUBLIC","provenance","DEMO","version",0,"reason","独立流程测试"))).path("id").asLong();}
    private HashMap<String,Object> draft(Long location){var d=new HashMap<String,Object>(Map.of("requestKey",key(),"version",0,"category","WATER","title","演示灯具故障","description","灯不亮","contact","private-demo@example.test","locationDetail","右侧第二盏灯","contactWindow","课后"));if(location!=null)d.put("locationId",location);return d;}
    private HashMap<String,Object> action(JsonNode r,Map<String,?> extra){var d=new HashMap<String,Object>(Map.of("requestKey",key(),"version",r.path("version").asInt(),"comment","人工确认处理说明"));if(extra!=null)d.putAll(extra);return d;}
    private HttpResponse<String> sendAction(Browser b,String view,JsonNode r,String action,Map<String,?> extra)throws Exception{return b.send("POST",ROOT+"/"+view+"/"+r.path("id").asLong()+"/"+action,action(r,extra));}
    private JsonNode act(Browser b,String view,JsonNode r,String action,Map<String,?> extra)throws Exception{return ok(sendAction(b,view,r,action,extra));}
    private Flow flow(String owner)throws Exception{long location=place(1);var s=login(owner);var r=ok(s.send("POST",ROOT+"/mine",draft(location)));r=act(s,"mine",r,"submit",null);return new Flow(s,login("dispatcher1001"),login("technician1001"),location,r);}
    private JsonNode assign(Flow f)throws Exception{return act(f.dispatcher(),"dispatch",f.request(),"assign",Map.of("technicianId",user("technician1001")));}
    private long actionCount(JsonNode r){return jdbc.sql("SELECT COUNT(*) FROM repair_action WHERE request_id=:id").param("id",r.path("id").asLong()).query(Long.class).single();}
    private long noticeCount(JsonNode r){return jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE source_type='REPAIR' AND source_id=:id").param("id",r.path("id").asLong()).query(Long.class).single();}
    private long otherWorker(long location){String name="other-"+key();jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) SELECT :name,password_hash,'另一演示维修人员',:name,1,department_id,TRUE FROM app_user WHERE username='technician1001'").param("name",name).update();long id=user(name);jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'REPAIR_TECHNICIAN')").param("id",id).update();jdbc.sql("INSERT INTO repair_staff_scope(user_id,capability,campus_id,category,location_id) VALUES(:id,'WORK',1,'WATER',:location)").param("id",id).param("location",location).update();return id;}
    private HttpResponse<String> unchecked(Browser b,JsonNode r,Map<String,?> a){try{return b.send("POST",ROOT+"/dispatch/"+r.path("id").asLong()+"/assign",a);}catch(Exception e){throw new RuntimeException(e);}}
    private byte[] png()throws Exception{var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray();}
    private JsonNode ok(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body());}
    private Browser login(String username)throws Exception{var b=new Browser();b.csrf();ok(b.send("POST","/api/auth/login",Map.of("username",username,"password","Demo@2026")));b.csrf();return b;}
    private record Flow(Browser owner,Browser dispatcher,Browser worker,long location,JsonNode request) {}
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token,header;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> upload(JsonNode r,String view,String purpose,byte[] bytes,String key)throws Exception{
            String boundary="repair-test-"+UUID.randomUUID();var body=new ByteArrayOutputStream();
            for(var field:Map.of("requestKey",key,"version",r.path("version").asText(),"purpose",purpose).entrySet())body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.send(builder(ROOT+"/"+view+"/"+r.path("id").asLong()+"/attachments").header(header,token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
