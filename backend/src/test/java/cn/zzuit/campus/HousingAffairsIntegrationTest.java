package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-housing-affairs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class HousingAffairsIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/housing/transfers";

    @Test void ownAffairsTrackReviewReservationAndCompletionWithoutPrivateFields() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var reviewer=login("affairs1001");
        String name=housedStudent(admin,"dorm1001");var student=login(name);
        long id=ok(student.send("POST",ROOT+"/mine",draft())).path("id").asLong();
        assertAffair(student,id,"DRAFT",0,1,0,0);
        ok(student.send("POST",ROOT+"/mine/"+id+"/submit",action(version(id),"提交")));
        assertAffair(student,id,"SUBMITTED",1,0,0,0);
        ok(reviewer.send("POST",ROOT+"/review/"+id+"/return",action(version(id),"请补充材料")));
        assertAffair(student,id,"RETURNED",0,0,1,0);
        var supplement=draft();supplement.put("version",version(id));
        ok(student.send("POST",ROOT+"/mine/"+id+"/supplement",supplement));
        ok(reviewer.send("POST",ROOT+"/review/"+id+"/approve",action(version(id),"同意")));
        assertAffair(student,id,"AWAITING_ASSIGNMENT",1,0,0,0);
        long target=extraBed(admin,buildingOf(currentBed(name)));
        ok(manager.send("POST",ROOT+"/assign/"+id+"/reserve",reserve(version(id),target)));
        assertAffair(student,id,"AWAITING_CHECKIN",1,0,0,0);
        ok(manager.send("POST",ROOT+"/assign/"+id+"/check-in",action(version(id),"核对入住")));
        assertAffair(student,id,"COMPLETED",0,0,0,1);
        assertThat(ok(login(student()).get("/api/affairs/mine?kind=HOUSING")).path("total").asInt()).isZero();
        assertThat(login(student()).get(ROOT+"/mine/"+id).statusCode()).isEqualTo(404);
    }
    @Test void terminalStatusesAndPaginationUseTheSameUnfilteredSummary() throws Exception {
        var admin=login("admin");String name=housedStudent(admin,"dorm1001");var student=login(name);
        for(String status:List.of("COMPLETED","REJECTED","WITHDRAWN","CANCELLED","EXPIRED")) {
            long id=ok(student.send("POST",ROOT+"/mine",draft())).path("id").asLong();
            jdbc.sql("UPDATE housing_transfer SET status=:status,current_student_id=NULL WHERE id=:id").param("status",status).param("id",id).update();
        }
        ok(student.send("POST",ROOT+"/mine",draft()));
        var page=ok(student.get("/api/affairs/mine?kind=HOUSING&pageSize=2&page=2"));
        assertThat(page.path("total").asInt()).isEqualTo(6);assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.path("summary").path("drafts").asInt()).isEqualTo(1);
        assertThat(page.path("summary").path("finished").asInt()).isEqualTo(5);
        assertThat(page.path("summary").path("pending").asInt()).isZero();
        var filtered=ok(student.get("/api/affairs/mine?kind=HOUSING&status=EXPIRED"));
        assertThat(filtered.path("total").asInt()).isEqualTo(1);assertThat(filtered.path("summary")).isEqualTo(page.path("summary"));
        assertThat(student.get("/api/affairs/mine?kind=HOUSING&status=UNKNOWN").statusCode()).isEqualTo(400);
    }
    @Test void reviewerMessagesOpenHistoricalDetailsAndRecheckCollege() throws Exception {
        var admin=login("admin");String reviewerName=account("har","审核老师","affairs1001","STUDENT_AFFAIRS");
        var reviewer=login(reviewerName);String name=housedStudent(admin,"dorm1001");var student=login(name);
        long id=ok(student.send("POST",ROOT+"/mine",draft())).path("id").asLong();
        var submit=action(version(id),"提交");ok(student.send("POST",ROOT+"/mine/"+id+"/submit",submit));
        long count=noticeCount(id);ok(student.send("POST",ROOT+"/mine/"+id+"/submit",submit));assertThat(noticeCount(id)).isEqualTo(count);
        assertThat(notice(reviewer,id).path("targetPath").asText()).isEqualTo("/student-affairs/housing?id="+id+"&tab=details");
        ok(reviewer.send("POST",ROOT+"/review/"+id+"/approve",action(version(id),"私密审核说明")));
        assertThat(ok(reviewer.get(ROOT+"/review?status=SUBMITTED")).toString()).doesNotContain("\"id\":"+id+",");
        assertThat(ok(reviewer.get(ROOT+"/review/"+id)).path("status").asText()).isEqualTo("AWAITING_ASSIGNMENT");
        assertThat(notice(student,id).toString()).doesNotContain("私密审核说明","quieter","年级证明");
        jdbc.sql("UPDATE app_user SET department_id=2 WHERE username=:name").param("name",reviewerName).update();
        assertThat(notice(reviewer,id).path("targetPath").isNull()).isTrue();
        assertThat(reviewer.get(ROOT+"/review/"+id).statusCode()).isEqualTo(404);
    }
    @Test void cancellationNotifiesOnlyCurrentTargetManagersAndHistoricalDetailsStayScoped() throws Exception {
        var admin=login("admin");String managerName=account("ham","专属宿管","dorm1001","DORM_MANAGER");var manager=login(managerName);
        String outsiderName=account("hao","无楼栋宿管","dorm1001","DORM_MANAGER");var outsider=login(outsiderName);
        String name=housedStudent(admin,managerName);var student=login(name);long building=buildingOf(currentBed(name));
        long id=reserve(student,login("affairs1001"),manager,extraBed(admin,building));
        var cancel=action(version(id),"个人隐私原因请求取消");ok(student.send("POST",ROOT+"/mine/"+id+"/cancel-request",cancel));
        long count=noticeCount(id);ok(student.send("POST",ROOT+"/mine/"+id+"/cancel-request",cancel));assertThat(noticeCount(id)).isEqualTo(count);
        assertThat(notice(manager,id).path("targetPath").asText()).isEqualTo("/housing/assignments?id="+id+"&tab=details");
        assertThat(notice(manager,id).toString()).doesNotContain("个人隐私原因");
        assertThat(ok(outsider.get("/api/notices")).toString()).doesNotContain("申请 #"+id+" ·");
        assertThat(outsider.get(ROOT+"/assign/"+id).statusCode()).isEqualTo(404);
        assertThat(ok(outsider.get(ROOT+"/assign?queue=waiting")).isEmpty()).isTrue();
        ok(manager.send("POST",ROOT+"/assign/"+id+"/keep",action(version(id),"按约定办理")));
        ok(manager.send("POST",ROOT+"/assign/"+id+"/check-in",action(version(id),"完成搬迁")));
        assertThat(ok(manager.get(ROOT+"/assign/"+id)).path("status").asText()).isEqualTo("COMPLETED");
        jdbc.sql("UPDATE dorm_manager_scope SET enabled=FALSE WHERE user_id=:user AND building_id=:building").param("user",user(managerName)).param("building",building).update();
        assertThat(notice(manager,id).path("targetPath").isNull()).isTrue();
        assertThat(manager.get(ROOT+"/assign/"+id).statusCode()).isEqualTo(404);
    }
    @Test void affairsExpireReservationsOnceWhileMessageListingRemainsReadOnly() throws Exception {
        var admin=login("admin");String name=housedStudent(admin,"dorm1001");var student=login(name);
        long source=currentBed(name),target=extraBed(admin,buildingOf(source));long id=reserve(student,login("affairs1001"),login("dorm1001"),target);
        jdbc.sql("UPDATE housing_transfer SET reserve_until=:until WHERE id=:id").param("until",LocalDateTime.now().minusHours(1)).param("id",id).update();
        long count=noticeCount(id);notice(student,id);assertThat(noticeCount(id)).isEqualTo(count);
        assertThat(jdbc.sql("SELECT status FROM housing_transfer WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("AWAITING_CHECKIN");
        assertAffair(student,id,"EXPIRED",0,0,0,1);assertAffair(student,id,"EXPIRED",0,0,0,1);
        assertThat(noticeCount(id)).isEqualTo(count+1);
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("VACANT");
        assertThat(currentBed(name)).isEqualTo(source);
        assertThat(notice(student,id).path("targetPath").asText()).isEqualTo("/housing/transfer?id="+id+"&tab=details");
    }
    @Test void revokedBusinessPermissionHidesHousingRowsSummaryAndMessageLinks() throws Exception {
        var admin=login("admin");String name=housedStudent(admin,"dorm1001");var student=login(name);
        long id=submitApproved(student,login("affairs1001"));String role="HA"+key().substring(2,14);
        jdbc.sql("INSERT INTO app_role(code,name) VALUES(:role,'限权学生')").param("role",role).update();
        jdbc.sql("INSERT INTO role_permission(role_code,permission_code) SELECT :role,permission_code FROM role_permission WHERE role_code='STUDENT' AND permission_code<>'housing:read'").param("role",role).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id=:user").param("user",user(name)).update();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:user,:role)").param("user",user(name)).param("role",role).update();
        var restricted=login(name);var all=ok(restricted.get("/api/affairs/mine"));
        assertThat(all.path("total").asInt()).isZero();assertThat(all.path("summary").path("total").asInt()).isZero();
        assertThat(restricted.get("/api/affairs/mine?kind=HOUSING").statusCode()).isEqualTo(403);
        assertThat(notice(restricted,id).path("targetPath").isNull()).isTrue();
        assertThat(restricted.get(ROOT+"/mine/"+id).statusCode()).isEqualTo(403);
    }
    @Test void notificationFailureRollsBackTheBusinessTransition() throws Exception {
        var admin=login("admin");String name=housedStudent(admin,"dorm1001");var student=login(name);var reviewer=login("affairs1001");
        long id=ok(student.send("POST",ROOT+"/mine",draft())).path("id").asLong();
        ok(student.send("POST",ROOT+"/mine/"+id+"/submit",action(version(id),"提交")));long count=noticeCount(id);int before=version(id);
        jdbc.sql("ALTER TABLE user_notice ADD CONSTRAINT housing_notice_failure CHECK(source_type <> 'HOUSING' OR source_id <> "+id+" OR source_view <> 'MINE')").update();
        try {
            assertThat(reviewer.send("POST",ROOT+"/review/"+id+"/approve",action(before,"同意")).statusCode()).isEqualTo(409);
            assertThat(ok(student.get(ROOT+"/mine/"+id)).path("status").asText()).isEqualTo("SUBMITTED");
            assertThat(version(id)).isEqualTo(before);assertThat(noticeCount(id)).isEqualTo(count);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_transfer_action WHERE transfer_id=:id AND action='APPROVE'").param("id",id).query(Long.class).single()).isZero();
        } finally {jdbc.sql("ALTER TABLE user_notice DROP CONSTRAINT housing_notice_failure").update();}
    }
    @Test void legacyAndForeignSourceMessagesNeverGuessDetailAccess() throws Exception {
        var admin=login("admin");String owner=housedStudent(admin,"dorm1001");long id=submitApproved(login(owner),login("affairs1001"));String name=student();var student=login(name);
        jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:user,'历史调宿消息','申请 #999999 已受理')").param("user",user(name)).update();
        jdbc.sql("INSERT INTO user_notice(user_id,title,body,source_type,source_id,source_view) VALUES(:user,'错误来源','普通提示','HOUSING',:id,'MINE')").param("user",user(name)).param("id",id).update();
        var notices=ok(student.get("/api/notices"));for(var notice:notices.path("items")) assertThat(notice.path("targetPath").isNull()).isTrue();
        assertThat(notices.toString()).doesNotContain("quieter","年级证明",owner);
    }
    private void assertAffair(Browser student,long id,String status,int pending,int drafts,int returned,int finished)throws Exception {
        var data=ok(student.get("/api/affairs/mine?kind=HOUSING&status="+status));
        assertThat(data.path("total").asInt()).isEqualTo(1);var item=data.path("items").get(0);
        assertThat(item.path("id").asLong()).isEqualTo(id);assertThat(item.path("name").asText()).isEqualTo("调宿申请");
        assertThat(item.path("targetPath").asText()).isEqualTo("/housing/transfer?id="+id+"&tab=details");
        assertThat(data.path("summary").path("pending").asInt()).isEqualTo(pending);assertThat(data.path("summary").path("drafts").asInt()).isEqualTo(drafts);
        assertThat(data.path("summary").path("returned").asInt()).isEqualTo(returned);assertThat(data.path("summary").path("finished").asInt()).isEqualTo(finished);
        assertThat(data.toString()).doesNotContain("quieter","年级证明","sourcePlace","materialNote","studentUsername");
    }
    private long noticeCount(long id){return jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE source_type='HOUSING' AND source_id=:id").param("id",id).query(Long.class).single();}
    private JsonNode notice(Browser actor,long id)throws Exception {for(var notice:ok(actor.get("/api/notices?pageSize=100")).path("items")) if(notice.path("body").asText().startsWith("申请 #"+id+" ·")) return notice;throw new AssertionError("Expected housing notice");}
    private long reserve(Browser student,Browser affairs,Browser manager,long bed)throws Exception{long id=submitApproved(student,affairs);return ok(manager.send("POST",ROOT+"/assign/"+id+"/reserve",reserve(version(id),bed))).path("id").asLong();}
    private long submitApproved(Browser student,Browser affairs)throws Exception{var created=ok(student.send("POST",ROOT+"/mine",draft()));var submitted=ok(student.send("POST",ROOT+"/mine/"+created.path("id").asLong()+"/submit",action(created.path("version").asInt(),"提交")));return ok(affairs.send("POST",ROOT+"/review/"+created.path("id").asLong()+"/approve",action(submitted.path("version").asInt(),"同意"))).path("id").asLong();}
    private int version(long id){return jdbc.sql("SELECT version FROM housing_transfer WHERE id=:id").param("id",id).query(Integer.class).single();}
    private String housedStudent(Browser admin,String manager)throws Exception{String name=student();long building=building(admin,1);long room=room(admin,building);long bed=bed(admin,room,"A");ok(admin.send("POST","/api/admin/housing/scopes",Map.of("userId",user(manager),"buildingId",building,"version",0,"reason","演示范围原因")));ok(login(manager).send("POST","/api/housing/assignments",Map.of("requestKey",key(),"studentId",user(name),"bedId",bed,"reason","初始入住")));return name;}
    private long extraBed(Browser admin,long building)throws Exception{return bed(admin,room(admin,building),"B");}
    private long buildingOf(long bedId){return jdbc.sql("SELECT r.building_id FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id WHERE d.id=:id").param("id",bedId).query(Long.class).single();}
    private long currentBed(String name){return jdbc.sql("SELECT bed_id FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single();}
    private long currentAssignment(String name){return jdbc.sql("SELECT id FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single();}
    private long building(Browser admin,long campus)throws Exception{return ok(admin.send("POST","/api/admin/housing/buildings",Map.of("campusId",campus,"code","DORM-"+UUID.randomUUID().toString().substring(0,8),"name","调宿测试楼","provenance","DEMO","version",0,"reason","演示配置原因"))).path("id").asLong();}
    private long room(Browser admin,long building)throws Exception{return ok(admin.send("POST","/api/admin/housing/rooms",Map.of("buildingId",building,"code","R-"+UUID.randomUUID().toString().substring(0,8),"name","201","floorLabel","2 层","version",0,"reason","演示配置原因"))).path("id").asLong();}
    private long bed(Browser admin,long room,String code)throws Exception{String label="B".equals(code)?"2 号床":"1 号床";return ok(admin.send("POST","/api/admin/housing/beds",Map.of("roomId",room,"code",code,"label",label,"version",0,"reason","演示配置原因"))).path("id").asLong();}
    private HashMap<String,Object> draft(){return new HashMap<>(Map.of("requestKey",key(),"version",0,"reason","希望换到 quieter 的楼层","preferredArea","东校区低楼层","materialNote","已准备年级证明"));}
    private Map<String,Object> action(int version,String comment){return Map.of("requestKey",key(),"version",version,"comment",comment);}
    private Map<String,Object> reserve(int version,long bed){return Map.of("requestKey",key(),"version",version,"bedId",bed,"reason","安排空闲床位");}
    private String key(){return "HT"+UUID.randomUUID().toString().replace("-","");}
    private String student(){return account("ht","调宿测试学生","20260001","STUDENT");}
    private String account(String prefix,String label,String source,String role){String name=prefix+UUID.randomUUID().toString().replace("-","").substring(0,12);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :u,password_hash,:n,:num,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username=:source").param("u",name).param("n",label).param("num",prefix.toUpperCase()+"-"+name).param("source",source).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",user(name)).param("role",role).update();return name;}
    private String usernameOf(Browser ignored){return jdbc.sql("SELECT username FROM app_user WHERE full_name='其他宿管' ORDER BY id DESC").query(String.class).list().get(0);}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private HttpResponse<String> sendUnchecked(Browser actor,String method,String path,Map<String,?> body){try{return actor.send(method,path,body);}catch(Exception ex){throw new RuntimeException(ex);}}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private class Browser{
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
