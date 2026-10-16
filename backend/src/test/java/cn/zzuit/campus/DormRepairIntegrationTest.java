package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import cn.zzuit.campus.repair.RepairReminderService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-dorm-repair-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.repair.reminders.enabled=false"})
class DormRepairIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired RepairReminderService reminders;
    final JsonMapper json=JsonMapper.builder().build();
    static final String SETTINGS="/api/admin/repair-settings/locations";
    @Test void entryRequiresLoginAndHousingPermissionAndActualStay() throws Exception {
        assertThat(new Browser().get("/api/repairs/my-dorm").statusCode()).isEqualTo(401);
        for(String name:List.of("teacher1001","dorm1001","admin"))assertThat(login(name).get("/api/repairs/my-dorm").statusCode()).isEqualTo(403);
        assertThat(ok(login(student()).get("/api/repairs/my-dorm")).path("occupied").asBoolean()).isFalse();
        var p=home();var s=student();checkIn(s,p.bed());var eligibility=ok(login(s).get("/api/repairs/my-dorm"));
        assertThat(eligibility.path("occupied").asBoolean()).isTrue();assertThat(eligibility.path("location").isNull()).isTrue();
        assertThat(eligibility.toString()).doesNotContain("bedId","studentId","housingAssignmentId");
    }
    @Test void sourceRequiresOneEnabledSameCampusRoomAndImmutableIdentity() throws Exception {
        var p=home();var a=login("admin");var fields=location(p.room());fields.put("campusId",2);
        assertThat(a.send("POST",SETTINGS,fields).statusCode()).isEqualTo(400);
        fields.put("campusId",1);fields.put("buildingId",9999);assertThat(a.send("POST",SETTINGS,fields).statusCode()).isEqualTo(400);
        fields.remove("buildingId");var row=ok(a.send("POST",SETTINGS,fields));
        assertThat(a.send("POST",SETTINGS,fields).statusCode()).isEqualTo(409);
        fields.put("dormRoomId",home().room());assertThat(a.send("PUT",SETTINGS+"/"+row.path("id").asLong(),fields).statusCode()).isEqualTo(409);
        var q=home();jdbc.sql("UPDATE dorm_room SET enabled=FALSE WHERE id=:id").param("id",q.room()).update();
        assertThat(a.send("POST",SETTINGS,location(q.room())).statusCode()).isEqualTo(400);
    }
    @Test void onlyCurrentResidentCanListResolveOrSavePrivateLocation() throws Exception {
        var f=resident();var stranger=login(student());var teacher=login("teacher1001");
        var own=ok(f.owner().get("/api/repairs/locations?pageSize=100"));
        assertThat(ids(own)).contains(f.location());
        for(var outsider:List.of(stranger,teacher)) {
            assertThat(ids(ok(outsider.get("/api/repairs/locations?pageSize=100")))).doesNotContain(f.location());
            assertThat(outsider.get("/api/repairs/locations/"+f.location()).statusCode()).isEqualTo(404);
            assertThat(outsider.send("POST","/api/repairs/mine",draft(f.location())).statusCode()).isEqualTo(404);
        }
        assertThat(ok(f.owner().get("/api/repairs/my-dorm")).path("location").path("id").asLong()).isEqualTo(f.location());
        assertThat(ok(f.owner().get("/api/repairs/locations/"+f.location())).toString()).doesNotContain("dormRoomId","bedId","studentId");
    }
    @Test void completeWorkflowUsesExistingDispatchWorkAndEvaluation() throws Exception {
        var f=resident();var row=submitted(f);row=act(login("dispatcher1001"),"dispatch",row,"assign",Map.of("technicianId",user("technician1001")));
        row=act(login("technician1001"),"work",row,"accept",Map.of());row=act(login("technician1001"),"work",row,"complete",Map.of());
        row=act(f.owner(),"mine",row,"confirm",Map.of("resultRound",1));
        row=act(f.owner(),"mine",row,"evaluation",Map.of("resultRound",1,"stars",5));
        assertThat(row.path("status").asText()).isEqualTo("COMPLETED");assertThat(row.path("evaluation").path("stars").asInt()).isEqualTo(5);
        assertThat(row.toString()).doesNotContain("housingAssignmentId","dormRoomId");
    }
    @Test void checkoutBlocksDraftSubmissionAndNewOccupantCannotReadHistory() throws Exception {
        var f=resident();var row=ok(f.owner().send("POST","/api/repairs/mine",draft(f.location())));checkout(f.student());
        assertThat(sendAction(f.owner(),"mine",row,"submit",Map.of()).statusCode()).isEqualTo(409);
        var history=ok(f.owner().get("/api/repairs/mine/"+row.path("id").asLong()));assertThat(history.path("locationEligible").asBoolean()).isFalse();
        assertThat(history.path("allowedActions").toString()).contains("edit","withdraw").doesNotContain("submit","upload-fault");
        var next=student();checkIn(next,f.place().bed());assertThat(login(next).get("/api/repairs/mine/"+row.path("id").asLong()).statusCode()).isEqualTo(404);
        assertThat(f.owner().get("/api/repairs/locations/"+f.location()).statusCode()).isEqualTo(404);
    }
    @Test void unsubmittedDraftCanBeReboundButSubmittedStayCannotBeRevivedOnReentry() throws Exception {
        var f=resident();var original=ok(f.owner().send("POST","/api/repairs/mine",draft(f.location())));checkout(f.student());
        var next=home();long location=open(next.room());checkIn(f.student(),next.bed());var update=draft(location);update.put("version",original.path("version").asInt());
        var changed=ok(f.owner().send("PUT","/api/repairs/mine/"+original.path("id").asLong(),update));assertThat(changed.path("locationEligible").asBoolean()).isTrue();
        var submitted=act(f.owner(),"mine",changed,"submit",Map.of());checkout(f.student());checkIn(f.student(),next.bed());
        assertThat(ok(f.owner().get("/api/repairs/mine/"+submitted.path("id").asLong())).path("locationEligible").asBoolean()).isFalse();
        assertThat(sendAction(login("dispatcher1001"),"dispatch",submitted,"assign",Map.of("technicianId",user("technician1001"))).statusCode()).isEqualTo(409);
        var returned=act(login("dispatcher1001"),"dispatch",submitted,"return",Map.of());
        assertThat(sendAction(f.owner(),"mine",returned,"submit",Map.of()).statusCode()).isEqualTo(409);
        assertThat(act(f.owner(),"mine",returned,"withdraw",Map.of()).path("status").asText()).isEqualTo("WITHDRAWN");
    }
    @Test void assignedWorkStopsAfterMoveAndCancellationStillClosesIt() throws Exception {
        var f=resident();var row=submitted(f);var dispatcher=login("dispatcher1001");row=act(dispatcher,"dispatch",row,"assign",Map.of("technicianId",user("technician1001")));
        var worker=login("technician1001");row=act(worker,"work",row,"accept",Map.of());checkout(f.student());checkIn(f.student(),home().bed());
        for(String operation:List.of("progress","complete","unable"))assertThat(sendAction(worker,"work",row,operation,Map.of()).statusCode()).isEqualTo(409);
        assertThat(ok(worker.get("/api/repairs/work/"+row.path("id").asLong())).path("allowedActions").isEmpty()).isTrue();
        var cancel=ok(f.owner().send("POST","/api/repairs/mine/"+row.path("id").asLong()+"/cancellations",Map.of("requestKey",key(),"version",row.path("version").asInt(),"comment","已调宿，停止旧房间维修")));
        var closed=ok(dispatcher.send("POST","/api/repairs/dispatch/"+row.path("id").asLong()+"/cancellation/decide",Map.of("requestKey",key(),"version",cancel.path("version").asInt(),"cancellationId",cancel.path("cancellation").path("id").asLong(),"decision","APPROVE","comment","核对调宿后关闭")));
        assertThat(closed.path("status").asText()).isEqualTo("CANCELLED");
    }
    @Test void endedStayDoesNotGenerateConfirmationReminders() throws Exception {
        var f=resident();var row=submitted(f);row=act(login("dispatcher1001"),"dispatch",row,"assign",Map.of("technicianId",user("technician1001")));
        row=act(login("technician1001"),"work",row,"accept",Map.of());row=act(login("technician1001"),"work",row,"complete",Map.of());
        jdbc.sql("UPDATE repair_completion SET created_at=DATEADD('HOUR',-72,CURRENT_TIMESTAMP) WHERE request_id=:id").param("id",row.path("id").asLong()).update();
        checkout(f.student());assertThat(reminders.processDue()).isZero();
        assertThat(sendAction(f.owner(),"mine",row,"confirm",Map.of("resultRound",1)).statusCode()).isEqualTo(409);
        assertThat(ok(f.owner().get("/api/repairs/summary")).path("mineAwaitingConfirmation").asLong()).isZero();
    }
    @Test void agentPreviewCannotBypassDormMembershipOrSaveAfterCheckout() throws Exception {
        var f=resident();var outsider=login(student());var fields=draft(f.location());fields.remove("requestKey");fields.remove("version");
        var input=Map.of("message","准备报修","mode","LOCAL","externalConsent",false,"fields",fields);
        var preview=ok(outsider.send("POST","/api/assistant/repair/prepare",input));assertThat(preview.path("ready").asBoolean()).isFalse();
        long session=ok(f.owner().send("POST","/api/assistant/sessions",Map.of("requestKey",key()))).path("id").asLong();
        var task=ok(f.owner().send("POST","/api/assistant/sessions/"+session+"/tasks",Map.of("requestKey",key(),"kind","REPAIR")));
        String path="/api/assistant/sessions/"+session+"/tasks/"+task.path("id").asLong();
        task=ok(f.owner().send("POST",path+"/repair",Map.of("version",0,"input",input)));assertThat(task.path("repairPreview").path("ready").asBoolean()).isTrue();
        checkout(f.student());assertThat(f.owner().send("POST",path+"/save",Map.of("version",task.path("version").asInt(),"confirmed",true)).statusCode()).isEqualTo(409);
    }
    @Test void stoppedSourceAndRevokedPermissionsTakeEffectImmediately() throws Exception {
        var f=resident();jdbc.sql("UPDATE dorm_building SET enabled=FALSE WHERE id=:id").param("id",f.place().building()).update();
        assertThat(ok(f.owner().get("/api/repairs/my-dorm")).path("location").isNull()).isTrue();
        assertThat(ids(ok(f.owner().get("/api/repairs/locations?pageSize=100")))).doesNotContain(f.location());
        assertThat(f.owner().send("POST","/api/repairs/mine",draft(f.location())).statusCode()).isEqualTo(400);
        jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='STUDENT'").param("u",user(f.student())).update();
        assertThat(f.owner().get("/api/repairs/my-dorm").statusCode()).isEqualTo(403);
    }
    @Test void actualTransferKeepsSourceEligibleUntilCheckInThenFreezesItsRepair() throws Exception {
        var f=resident();var repair=submitted(f);var target=home();long targetLocation=open(target.room());
        String root="/api/housing/transfers";
        var transfer=ok(f.owner().send("POST",root+"/mine",Map.of("requestKey",key(),"version",0,"reason","调宿联动验收","preferredArea","东校区","materialNote","调宿原因已与学院核对")));
        long id=transfer.path("id").asLong();
        transfer=ok(f.owner().send("POST",root+"/mine/"+id+"/submit",transferAction(transfer)));
        transfer=ok(login("affairs1001").send("POST",root+"/review/"+id+"/approve",transferAction(transfer)));
        transfer=ok(login("dorm1001").send("POST",root+"/assign/"+id+"/reserve",Map.of("requestKey",key(),"version",transfer.path("version").asInt(),"bedId",target.bed(),"reason","预留验收床位")));
        assertThat(ok(f.owner().get("/api/repairs/mine/"+repair.path("id").asLong())).path("locationEligible").asBoolean()).isTrue();
        assertThat(ok(f.owner().get("/api/repairs/my-dorm")).path("location").path("id").asLong()).isEqualTo(f.location());
        ok(login("dorm1001").send("POST",root+"/assign/"+id+"/check-in",transferAction(transfer)));
        assertThat(ok(f.owner().get("/api/repairs/mine/"+repair.path("id").asLong())).path("locationEligible").asBoolean()).isFalse();
        assertThat(ok(f.owner().get("/api/repairs/my-dorm")).path("location").path("id").asLong()).isEqualTo(targetLocation);
        assertThat(f.owner().send("POST","/api/repairs/mine",draft(f.location())).statusCode()).isEqualTo(404);
    }
    private Map<String,Object> transferAction(JsonNode row){return Map.of("requestKey",key(),"version",row.path("version").asInt(),"comment","核对调宿联动");}
    @Test void currentGuideDescribesResidentOnlyDormRepairs() throws Exception {
        var guide=ok(login("20260001").get("/api/assistant/guides/repair"));
        assertThat(guide.path("body").asText()).contains("本人当前入住的宿舍房间","旧工单保留历史").doesNotContain("私人宿舍房间暂不开放");
        assertThat(guide.path("version").asInt()).isEqualTo(2);
    }
    private Set<Long> ids(JsonNode page){Set<Long> result=new HashSet<>();page.path("items").forEach(n->result.add(n.path("id").asLong()));return result;}
    private Fixture resident() throws Exception {var p=home();long location=open(p.room());String student=student();checkIn(student,p.bed());return new Fixture(student,login(student),p,location);}
    private long open(long room) throws Exception {return ok(login("admin").send("POST",SETTINGS,location(room))).path("id").asLong();}
    private HashMap<String,Object> location(long room){return new HashMap<>(Map.of("campusId",1,"code",key(),"name","独立验收宿舍房间","kind","DORM_ROOM","dormRoomId",room,"provenance","DEMO","version",0,"reason","宿舍报修验收配置"));}
    private Place home() throws Exception {
        var admin=login("admin");long building=ok(admin.send("POST","/api/admin/housing/buildings",Map.of("campusId",1,"code",key(),"name","独立验收宿舍楼","provenance","DEMO","version",0,"reason","测试配置"))).path("id").asLong();
        long room=ok(admin.send("POST","/api/admin/housing/rooms",Map.of("buildingId",building,"code",key(),"name","101","floorLabel","1 层","version",0,"reason","测试配置"))).path("id").asLong();
        long bed=ok(admin.send("POST","/api/admin/housing/beds",Map.of("roomId",room,"code","A","label","1号床","version",0,"reason","测试配置"))).path("id").asLong();
        ok(admin.send("POST","/api/admin/housing/scopes",Map.of("userId",user("dorm1001"),"buildingId",building,"version",0,"reason","测试范围")));return new Place(building,room,bed);
    }
    private void checkIn(String student,long bed) throws Exception {ok(login("dorm1001").send("POST","/api/housing/assignments",Map.of("requestKey",key(),"studentId",user(student),"bedId",bed,"reason","测试入住")));}
    private void checkout(String student) throws Exception {var row=jdbc.sql("SELECT id,version FROM housing_assignment WHERE current_student_id=:u").param("u",user(student)).query((r,n)->new long[]{r.getLong(1),r.getLong(2)}).single();ok(login("dorm1001").send("POST","/api/housing/assignments/"+row[0]+"/checkout",Map.of("requestKey",key(),"version",row[1],"reason","验收退宿或调宿前结束原入住")));}
    private String student(){String name="dr"+key().replace("-","").substring(0,12);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :u,password_hash,'报修测试学生',:u,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username='20260001'").param("u",name).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'STUDENT')").param("u",user(name)).update();return name;}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private String key(){return UUID.randomUUID().toString();}
    private HashMap<String,Object> draft(long location){return new HashMap<>(Map.of("requestKey",key(),"version",0,"locationId",location,"category","WATER","title","洗手台漏水","description","水管接头滴水","contact","dorm-owner@example.test"));}
    private JsonNode submitted(Fixture f) throws Exception {return act(f.owner(),"mine",ok(f.owner().send("POST","/api/repairs/mine",draft(f.location()))),"submit",Map.of());}
    private HttpResponse<String> sendAction(Browser b,String view,JsonNode row,String action,Map<String,?> extra) throws Exception {var body=new HashMap<String,Object>(Map.of("requestKey",key(),"version",row.path("version").asInt(),"comment","人工核对后办理"));body.putAll(extra);return b.send("POST","/api/repairs/"+view+"/"+row.path("id").asLong()+"/"+action,body);}
    private JsonNode act(Browser b,String view,JsonNode row,String action,Map<String,?> extra) throws Exception {return ok(sendAction(b,view,row,action,extra));}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private Browser login(String name) throws Exception {var b=new Browser();b.csrf();ok(b.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));b.csrf();return b;}
    private record Place(long building,long room,long bed) {}
    private record Fixture(String student,Browser owner,Place place,long location) {}
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path) throws Exception {return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf() throws Exception {var d=json.readTree(get("/api/auth/csrf").body());header=d.path("headerName").asText();token=d.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception {return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
