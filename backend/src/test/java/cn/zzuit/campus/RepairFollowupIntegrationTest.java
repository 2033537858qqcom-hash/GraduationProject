package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.zzuit.campus.repair.RepairReminderService;
import java.net.*;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-repair-followup-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.repair.reminders.enabled=false"})
class RepairFollowupIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired RepairReminderService reminders;
    @MockitoBean(name="repairClock") Clock clock;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/repairs";
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,12,12,0);
    @BeforeEach void resetTime(){setTime(NOW);}
    // Independent in-memory fixtures: close old active requests so advancing the test clock cannot notify earlier cases.
    @AfterEach void closeFixtures(){jdbc.sql("UPDATE repair_request SET status='WITHDRAWN',current_cancellation_id=NULL").update();}
    @Test void cancellationPreservesMainStateAndPausesWorkerAndReporterActions() throws Exception {
        var f=flow("20260001");var assigned=assign(f);var pending=cancel(f.owner(),assigned,"request",null);
        assertThat(pending.path("status").asText()).isEqualTo("ASSIGNED");assertThat(pending.path("cancellation").path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("allowedActions").toString()).contains("withdraw-cancellation").doesNotContain("confirm");
        assertThat(ok(f.worker().get(ROOT+"/work/"+pending.path("id").asLong())).path("allowedActions").size()).isZero();
        assertThat(sendAction(f.worker(),"work",pending,"accept",null).statusCode()).isEqualTo(409);
        assertThat(sendAction(f.dispatcher(),"dispatch",pending,"reassign",Map.of("technicianId",user("technician1001"))).statusCode()).isEqualTo(409);
        assertThat(ok(f.worker().get(ROOT+"/summary")).path("workAssigned").asLong()).isZero();
        assertThat(ok(f.dispatcher().get(ROOT+"/summary")).path("dispatchCancellationPending").asLong()).isEqualTo(1);
        assertThat(ok(f.dispatcher().get(ROOT+"/dispatch?cancellationPending=true")).path("items").toString()).contains("\"cancellationPending\":true");
    }
    @Test void rejectedCancellationResumesTheUnchangedProcessingState() throws Exception {
        var f=flow("teacher1001");var r=act(f.worker(),"work",assign(f),"accept",null);r=cancel(f.owner(),r,"request",null);r=cancel(f.dispatcher(),r,"decide","REJECT");
        assertThat(r.path("status").asText()).isEqualTo("IN_PROGRESS");assertThat(r.path("cancellation").path("status").asText()).isEqualTo("REJECTED");
        assertThat(act(f.worker(),"work",r,"complete",null).path("status").asText()).isEqualTo("AWAITING_CONFIRMATION");
    }
    @Test void reporterWithdrawsCancellationAndCanConfirmOriginalResult() throws Exception {
        var f=flow("20260001");var r=awaiting(f);r=cancel(f.owner(),r,"request",null);r=cancel(f.owner(),r,"withdraw",null);
        assertThat(r.path("status").asText()).isEqualTo("AWAITING_CONFIRMATION");assertThat(r.path("completionRound").asInt()).isEqualTo(1);
        assertThat(r.path("cancellation").path("status").asText()).isEqualTo("WITHDRAWN");assertThat(act(f.owner(),"mine",r,"confirm",Map.of("resultRound",1)).path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void approvedCancellationClosesAssignmentAndUnifiedAffairs() throws Exception {
        var f=flow("20260001");var r=cancel(f.owner(),assign(f),"request",null);r=cancel(f.dispatcher(),r,"decide","APPROVE");long id=r.path("id").asLong();
        assertThat(r.path("status").asText()).isEqualTo("CANCELLED");assertThat(r.path("technicianId").isNull()).isTrue();assertThat(r.path("confirmedAt").isNull()).isTrue();
        assertThat(f.worker().get(ROOT+"/work/"+id).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM repair_assignment WHERE request_id=:id AND ended_at IS NULL").param("id",id).query(Long.class).single()).isZero();
        assertThat(ok(f.owner().get("/api/affairs/mine?kind=REPAIR&status=CANCELLED")).path("items").toString()).contains("CANCELLED");
        assertThat(ok(f.owner().get("/api/affairs/mine?kind=REPAIR&status=CANCELLED")).path("summary").path("finished").asLong()).isGreaterThan(0);
        assertThat(sendAction(f.owner(),"mine",r,"evaluation",Map.of("resultRound",1,"stars",5)).statusCode()).isEqualTo(409);
    }
    @Test void repeatedCancellationAndDecisionHaveNoDuplicateRowsHistoryOrNotices() throws Exception {
        var f=flow("20260001");var original=assign(f);var request=cancelBody(original,"request",null);var r=ok(f.owner().send("POST",cancelPath(original,"request"),request));
        long actions=actionCount(r),notices=noticeCount(r);assertThat(ok(f.owner().send("POST",cancelPath(original,"request"),request)).path("version").asInt()).isEqualTo(r.path("version").asInt());
        assertThat(actionCount(r)).isEqualTo(actions);assertThat(noticeCount(r)).isEqualTo(notices);
        var decision=cancelBody(r,"decide","APPROVE");var closed=ok(f.dispatcher().send("POST",cancelPath(r,"decide"),decision));
        actions=actionCount(r);notices=noticeCount(r);assertThat(ok(f.dispatcher().send("POST",cancelPath(r,"decide"),decision)).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(actionCount(closed)).isEqualTo(actions);assertThat(noticeCount(closed)).isEqualTo(notices);
        decision.put("comment","变更意见");assertThat(f.dispatcher().send("POST",cancelPath(r,"decide"),decision).statusCode()).isEqualTo(409);
    }
    @Test void currentCancellationIdentityAndVersionPreventStaleDecisions() throws Exception {
        var f=flow("20260001");var r=cancel(f.owner(),assign(f),"request",null);long old=r.path("cancellation").path("id").asLong();r=cancel(f.owner(),r,"withdraw",null);r=cancel(f.owner(),r,"request",null);
        var invalid=cancelBody(r,"decide","APPROVE");invalid.put("cancellationId",old);assertThat(f.dispatcher().send("POST",cancelPath(r,"decide"),invalid).statusCode()).isEqualTo(409);
        invalid=cancelBody(r,"decide","REJECT");invalid.put("version",0);assertThat(f.dispatcher().send("POST",cancelPath(r,"decide"),invalid).statusCode()).isEqualTo(409);
        assertThat(ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong())).path("cancellation").path("status").asText()).isEqualTo("PENDING");
    }
    @Test void onlyOwnerAndCurrentDispatcherScopeCanProcessCancellation() throws Exception {
        var f=flow("20260001");var r=assign(f);assertThat(login("20260002").send("POST",cancelPath(r,"request"),cancelBody(r,"request",null)).statusCode()).isEqualTo(404);
        r=cancel(f.owner(),r,"request",null);assertThat(f.worker().send("POST",cancelPath(r,"decide"),cancelBody(r,"decide","APPROVE")).statusCode()).isEqualTo(403);
        jdbc.sql("UPDATE repair_staff_scope SET enabled=FALSE WHERE user_id=:id AND category='WATER'").param("id",user("dispatcher1001")).update();
        try{assertThat(f.dispatcher().send("POST",cancelPath(r,"decide"),cancelBody(r,"decide","APPROVE")).statusCode()).isEqualTo(404);}
        finally{jdbc.sql("UPDATE repair_staff_scope SET enabled=TRUE WHERE user_id=:id AND category='WATER'").param("id",user("dispatcher1001")).update();}
    }
    @Test void unassignedAndFinishedRequestsCannotStartCancellation() throws Exception {
        var f=flow("20260001");assertThat(f.owner().send("POST",cancelPath(f.request(),"request"),cancelBody(f.request(),"request",null)).statusCode()).isEqualTo(409);
        var r=act(f.owner(),"mine",awaiting(f),"confirm",Map.of("resultRound",1));assertThat(f.owner().send("POST",cancelPath(r,"request"),cancelBody(r,"request",null)).statusCode()).isEqualTo(409);
    }
    @Test void concurrentCancellationRequestsHaveOnePendingRecord() throws Exception {
        var f=flow("20260001");var r=assign(f);var a=cancelBody(r,"request",null);var b=cancelBody(r,"request",null);
        var first=CompletableFuture.supplyAsync(()->cancelUnchecked(f.owner(),r,a));var second=CompletableFuture.supplyAsync(()->cancelUnchecked(f.owner(),r,b));
        assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM repair_cancellation WHERE request_id=:id AND status='PENDING'").param("id",r.path("id").asLong()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void cancellationAndResultConfirmationRaceHasOneWinner() throws Exception {
        var f=flow("20260001");var r=awaiting(f);var a=cancelBody(r,"request",null);var b=action(r,Map.of("resultRound",1));
        var first=CompletableFuture.supplyAsync(()->cancelUnchecked(f.owner(),r,a));
        var second=CompletableFuture.supplyAsync(()->{try{return f.owner().send("POST",ROOT+"/mine/"+r.path("id").asLong()+"/confirm",b);}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        var latest=ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong()));assertThat(latest.path("status").asText()).isIn("COMPLETED","AWAITING_CONFIRMATION");
        assertThat(latest.path("cancellation").isNull()).isEqualTo(latest.path("status").asText().equals("COMPLETED"));
    }
    @Test void failedCancellationNoticeRollsBackPointerHistoryAndRequest() throws Exception {
        var f=flow("20260001");var r=assign(f);long id=r.path("id").asLong();long before=actionCount(r);
        jdbc.sql("ALTER TABLE user_notice ADD CONSTRAINT repair_cancel_failure CHECK(source_id<>"+id+" OR title NOT LIKE '%取消%')").update();
        try{assertThat(f.owner().send("POST",cancelPath(r,"request"),cancelBody(r,"request",null)).statusCode()).isEqualTo(409);assertThat(actionCount(r)).isEqualTo(before);assertThat(ok(f.owner().get(ROOT+"/mine/"+id)).path("cancellation").isNull()).isTrue();}
        finally{jdbc.sql("ALTER TABLE user_notice DROP CONSTRAINT repair_cancel_failure").update();}
    }
    @Test void statisticsUseFirstSubmissionHalfOpenDatesAndCompletedSampleAverage() throws Exception {
        var f=flow("20260001");var second=flow("20260001");var cancelled=flow("20260001");var returned=flow("20260001");var excluded=flow("20260001");
        timestamp(f.request(),"2020-01-01T00:00:00","COMPLETED","2020-01-01T01:00:00");timestamp(second.request(),"2020-01-02T23:00:00","COMPLETED","2020-01-03T01:00:00");
        timestamp(cancelled.request(),"2020-01-02T12:00:00","CANCELLED",null);timestamp(returned.request(),"2020-01-02T15:00:00","RETURNED",null);timestamp(excluded.request(),"2020-01-03T00:00:00","SUBMITTED",null);
        var stats=ok(f.dispatcher().get(ROOT+"/dispatch/statistics?from=2020-01-01&to=2020-01-03"));assertThat(stats.path("total").asLong()).isEqualTo(4);assertThat(stats.path("completed").asLong()).isEqualTo(2);assertThat(stats.path("cancelled").asLong()).isEqualTo(1);assertThat(stats.path("averageSampleCount").asLong()).isEqualTo(2);assertThat(stats.path("averageHours").asDouble()).isEqualTo(1.5);
        assertThat(stats.toString()).doesNotContain("contact","reporterName","private-demo","description");
    }
    @Test void statisticsRespectCurrentScopeRejectOtherRolesAndShowNoSampleAsNull() throws Exception {
        var f=flow("20260001");timestamp(f.request(),"2019-01-01T12:00:00","SUBMITTED",null);
        var stats=ok(f.dispatcher().get(ROOT+"/dispatch/statistics?from=2019-01-01&to=2019-01-02"));assertThat(stats.path("total").asLong()).isEqualTo(1);assertThat(stats.path("averageHours").isNull()).isTrue();
        for(String name:List.of("admin","20260001","teacher1001","technician1001"))assertThat(login(name).get(ROOT+"/dispatch/statistics").statusCode()).isEqualTo(403);
        assertThat(f.dispatcher().get(ROOT+"/dispatch/statistics?from=2026-01-01&to=2028-01-01").statusCode()).isEqualTo(400);
        jdbc.sql("UPDATE repair_staff_scope SET enabled=FALSE WHERE user_id=:id AND category='WATER'").param("id",user("dispatcher1001")).update();
        try{assertThat(ok(f.dispatcher().get(ROOT+"/dispatch/statistics?from=2019-01-01&to=2019-01-02")).path("total").asLong()).isZero();}finally{jdbc.sql("UPDATE repair_staff_scope SET enabled=TRUE WHERE user_id=:id AND category='WATER'").param("id",user("dispatcher1001")).update();}
    }
    @Test void reminderIsDailyPerResultWithoutCompletingOrChangingVersion() throws Exception {
        var f=flow("20260001");var r=awaiting(f);age(r,NOW.minusHours(49));assertThat(reminders.processDue()).isEqualTo(1);assertThat(reminders.processDue()).isZero();
        var current=ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong()));assertThat(current.path("version").asInt()).isEqualTo(r.path("version").asInt());assertThat(current.path("status").asText()).isEqualTo("AWAITING_CONFIRMATION");assertThat(current.path("evaluation").isNull()).isTrue();
        setTime(NOW.plusDays(1));assertThat(reminders.processDue()).isEqualTo(1);
    }
    @Test void pendingCancellationPausesRemindersAndWithdrawalResumesThem() throws Exception {
        var f=flow("20260001");var r=awaiting(f);age(r,NOW.minusHours(49));r=cancel(f.owner(),r,"request",null);assertThat(reminders.processDue()).isZero();
        assertThat(ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong())).path("confirmationOverdue").asBoolean()).isFalse();
        cancel(f.owner(),r,"withdraw",null);assertThat(reminders.processDue()).isEqualTo(1);
    }
    @Test void reminderChecksCurrentResultRoundAndSkipsClosedOrDisabledReporter() throws Exception {
        var f=flow("20260001");var r=awaiting(f);age(r,NOW.minusHours(49));assertThat(reminders.processDue()).isEqualTo(1);
        r=act(f.owner(),"mine",r,"reopen",Map.of("resultRound",1));r=act(f.worker(),"work",r,"complete",null);age(r,NOW.minusHours(49));assertThat(reminders.processDue()).isEqualTo(1);
        act(f.owner(),"mine",r,"confirm",Map.of("resultRound",2));setTime(NOW.plusDays(1));assertThat(reminders.processDue()).isZero();
        var other=flow("20260002");var second=awaiting(other);age(second,NOW.minusHours(49));jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE username='20260002'").update();
        try{assertThat(reminders.processDue()).isZero();}finally{jdbc.sql("UPDATE app_user SET enabled=TRUE WHERE username='20260002'").update();}
    }
    @Test void reminderNoticeFailureRollsBackDeduplicationRecordAndCanRetry() throws Exception {
        var f=flow("20260001");var r=awaiting(f);age(r,NOW.minusHours(49));long id=r.path("id").asLong();
        jdbc.sql("ALTER TABLE user_notice ADD CONSTRAINT repair_reminder_failure CHECK(source_id<>"+id+" OR title<>'报修结果待你验收')").update();
        try{assertThatThrownBy(()->reminders.processDue()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThat(jdbc.sql("SELECT COUNT(*) FROM repair_reminder WHERE request_id=:id").param("id",id).query(Long.class).single()).isZero();}
        finally{jdbc.sql("ALTER TABLE user_notice DROP CONSTRAINT repair_reminder_failure").update();}
        assertThat(reminders.processDue()).isEqualTo(1);
    }
    @Test void overdueBoundaryIsExactly48HoursAndNotificationBatchDoesNotStarveLaterRows() throws Exception {
        var f=flow("20260001");var r=awaiting(f);age(r,NOW.minusHours(48).plusSeconds(1));assertThat(ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong())).path("confirmationOverdue").asBoolean()).isFalse();
        age(r,NOW.minusHours(48));assertThat(ok(f.owner().get(ROOT+"/mine/"+r.path("id").asLong())).path("confirmationOverdue").asBoolean()).isTrue();
        assertThat(reminders.processDue()).isEqualTo(1);
        for(int i=0;i<102;i++) {
            String k=key();jdbc.sql("INSERT INTO repair_request(reporter_id,reporter_name,request_key,campus_id,location_id,category,title,description,contact,status,completion_round,submitted_at) VALUES(:user,'提醒测试',:key,1,:location,'WATER','演示故障','演示现象','reminder@example.test','AWAITING_CONFIRMATION',1,:time)")
                .param("user",user("20260001")).param("key",k).param("location",f.location()).param("time",Timestamp.valueOf(NOW.minusDays(4))).update();
            long id=jdbc.sql("SELECT id FROM repair_request WHERE request_key=:key").param("key",k).query(Long.class).single();jdbc.sql("INSERT INTO repair_completion(request_id,result_round,technician_id,technician_name,comment,created_at) VALUES(:id,1,:tech,'演示维修','演示结果',:time)")
                .param("id",id).param("tech",user("technician1001")).param("time",Timestamp.valueOf(NOW.minusHours(49))).update();
        }
        assertThat(reminders.processDue()).isEqualTo(100);assertThat(reminders.processDue()).isEqualTo(2);assertThat(reminders.processDue()).isZero();
    }
    private void setTime(LocalDateTime t){when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));when(clock.instant()).thenReturn(t.atZone(ZoneId.of("Asia/Shanghai")).toInstant());}
    private JsonNode awaiting(Flow f)throws Exception {var r=act(f.worker(),"work",assign(f),"accept",null);return act(f.worker(),"work",r,"complete",null);}
    private void age(JsonNode r,LocalDateTime time){jdbc.sql("UPDATE repair_completion SET created_at=:time WHERE request_id=:id AND result_round=:round").param("time",Timestamp.valueOf(time)).param("id",r.path("id").asLong()).param("round",r.path("completionRound").asInt()).update();}
    private void timestamp(JsonNode r,String submitted,String status,String confirmed){jdbc.sql("UPDATE repair_request SET submitted_at=:submitted,status=:status,confirmed_at=:confirmed WHERE id=:id").param("submitted",Timestamp.valueOf(LocalDateTime.parse(submitted))).param("status",status).param("confirmed",confirmed==null?null:Timestamp.valueOf(LocalDateTime.parse(confirmed))).param("id",r.path("id").asLong()).update();}
    private String cancelPath(JsonNode r,String operation){return ROOT+"/"+(operation.equals("decide")?"dispatch/":"mine/")+r.path("id").asLong()+(operation.equals("decide")?"/cancellation/decide":operation.equals("withdraw")?"/cancellations/current/withdraw":"/cancellations");}
    private HashMap<String,Object> cancelBody(JsonNode r,String operation,String decision){var body=action(r,Map.of("comment","独立测试取消及处理意见"));if(!operation.equals("request"))body.put("cancellationId",r.path("cancellation").path("id").asLong());if(decision!=null)body.put("decision",decision);return body;}
    private JsonNode cancel(Browser b,JsonNode r,String operation,String decision)throws Exception{return ok(b.send("POST",cancelPath(r,operation),cancelBody(r,operation,decision)));}
    private HttpResponse<String> cancelUnchecked(Browser b,JsonNode r,Map<String,?> body){try{return b.send("POST",cancelPath(r,"request"),body);}catch(Exception e){throw new RuntimeException(e);}}
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

