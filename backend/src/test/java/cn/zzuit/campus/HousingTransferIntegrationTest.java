package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.housing.HousingTransferService;
import java.net.*;
import java.net.http.*;
import java.time.LocalDateTime;
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
    "spring.datasource.url=jdbc:h2:mem:campus-housing-transfer;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class HousingTransferIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired HousingTransferService transfers;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/housing/transfers";
    @Test void transferRequiresTheMatchingRoleAndCurrentHousing() throws Exception {
        assertThat(new Browser().get(ROOT+"/mine").statusCode()).isEqualTo(401);
        var student=login(student());
        assertThat(student.get(ROOT+"/review").statusCode()).isEqualTo(403);
        assertThat(login("dorm1001").get(ROOT+"/mine").statusCode()).isEqualTo(403);
        assertThat(login("affairs1001").send("POST",ROOT+"/mine",draft()).statusCode()).isEqualTo(403);
        assertThat(student.send("POST",ROOT+"/mine",draft()).statusCode()).isEqualTo(409);
    }
    @Test void reviewReserveAndCheckInSwitchesBedsAtomically() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var affairs=login("affairs1001");
        var name=housedStudent(admin,"dorm1001");var student=login(name);long source=currentBed(name);
        var target=extraBed(admin,buildingOf(source));
        var created=ok(student.send("POST",ROOT+"/mine",draft()));
        var submitted=ok(student.send("POST",ROOT+"/mine/"+created.path("id").asLong()+"/submit",action(created.path("version").asInt(),"提交")));
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("VACANT");
        var approved=ok(affairs.send("POST",ROOT+"/review/"+created.path("id").asLong()+"/approve",action(submitted.path("version").asInt(),"同意转交宿管")));
        var reserved=ok(manager.send("POST",ROOT+"/assign/"+created.path("id").asLong()+"/reserve",reserve(approved.path("version").asInt(),target)));
        assertThat(reserved.path("status").asText()).isEqualTo("AWAITING_CHECKIN");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",source).query(String.class).single()).isEqualTo("OCCUPIED");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("RESERVED");
        var done=ok(manager.send("POST",ROOT+"/assign/"+created.path("id").asLong()+"/check-in",action(reserved.path("version").asInt(),"已核对搬入")));
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",source).query(String.class).single()).isEqualTo("VACANT");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("OCCUPIED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single()).isEqualTo(1);
        assertThat(ok(student.get("/api/housing/mine")).path("current").path("bedLabel").asText()).isEqualTo("2 号床");
        assertThat(manager.send("POST","/api/housing/assignments/"+currentAssignment(name)+"/checkout",Map.of("requestKey",key(),"version",0,"reason","调宿后退宿")).statusCode()).isEqualTo(200);
    }
    @Test void onlyOneReservationWinsAndOriginalStayRemainsForTheLoser() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var affairs=login("affairs1001");
        var first=housedStudent(admin,"dorm1001");var second=housedStudent(admin,"dorm1001");
        long shared=extraBed(admin,buildingOf(currentBed(first)));
        long left=submitApproved(login(first),affairs);long right=submitApproved(login(second),affairs);
        var one=CompletableFuture.supplyAsync(()->sendUnchecked(manager,"POST",ROOT+"/assign/"+left+"/reserve",reserve(version(left),shared)));
        var two=CompletableFuture.supplyAsync(()->sendUnchecked(manager,"POST",ROOT+"/assign/"+right+"/reserve",reserve(version(right),shared)));
        assertThat(List.of(one.get().statusCode(),two.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_transfer WHERE reserved_bed_id=:id").param("id",shared).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM housing_assignment WHERE current_student_id=:id").param("id",user(second)).query(String.class).list()).contains("CURRENT");
    }
    @Test void cancelAndExpiryReleaseTheReservationWithoutMovingTheStudent() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var affairs=login("affairs1001");
        var name=housedStudent(admin,"dorm1001");long source=currentBed(name);long target=extraBed(admin,buildingOf(source));
        long id=reserve(login(name),affairs,manager,target);
        var requested=ok(login(name).send("POST",ROOT+"/mine/"+id+"/cancel-request",action(version(id),"暂时不搬")));
        assertThat(manager.send("POST",ROOT+"/assign/"+id+"/check-in",action(requested.path("version").asInt(),"同时入住")).statusCode()).isEqualTo(409);
        ok(manager.send("POST",ROOT+"/assign/"+id+"/cancel",action(requested.path("version").asInt(),"同意取消")));
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("VACANT");
        assertThat(jdbc.sql("SELECT bed_id FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single()).isEqualTo(source);
        long again=reserve(login(name),affairs,manager,target);
        jdbc.sql("UPDATE housing_transfer SET reserve_until=:until WHERE id=:id").param("until",java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(5))).param("id",again).update();
        assertThat(transfers.processDue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM housing_transfer WHERE id=:id").param("id",again).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("VACANT");
        assertThat(jdbc.sql("SELECT bed_id FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single()).isEqualTo(source);
    }
    @Test void changedSourceVersionBlocksCheckInAndKeepsBothBeds() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var affairs=login("affairs1001");
        var name=housedStudent(admin,"dorm1001");long source=currentBed(name);long target=extraBed(admin,buildingOf(source));
        long id=reserve(login(name),affairs,manager,target);int version=version(id);
        jdbc.sql("UPDATE housing_assignment SET version=version+1 WHERE current_student_id=:id").param("id",user(name)).update();
        assertThat(manager.send("POST",ROOT+"/assign/"+id+"/check-in",action(version,"入住")).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT status FROM housing_transfer WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("AWAITING_CHECKIN");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",source).query(String.class).single()).isEqualTo("OCCUPIED");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",target).query(String.class).single()).isEqualTo("RESERVED");
    }
    @Test void scopeAndCollegeBoundariesHidePrivateTransfers() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var outsider=login(account("hm","其他宿管","dorm1001","DORM_MANAGER"));
        var affairs=login("affairs1001");var name=housedStudent(admin,"dorm1001");long source=currentBed(name);
        long foreignBuilding=building(admin,2);long foreignBed=bed(admin,room(admin,foreignBuilding),"B");
        ok(admin.send("POST","/api/admin/housing/scopes",Map.of("userId",user(usernameOf(outsider)),"buildingId",foreignBuilding,"version",0,"reason","其他楼栋范围")));
        long id=submitApproved(login(name),affairs);
        assertThat(outsider.send("POST",ROOT+"/assign/"+id+"/reserve",reserve(version(id),currentBed(name))).statusCode()).isEqualTo(404);
        assertThat(manager.send("POST",ROOT+"/assign/"+id+"/reserve",reserve(version(id),foreignBed)).statusCode()).isEqualTo(404);
        var other=login(student());
        assertThat(other.get(ROOT+"/mine").toString()).doesNotContain(name);
        jdbc.sql("UPDATE app_user SET department_id=2 WHERE username=:u").param("u",name).update();
        assertThat(affairs.get(ROOT+"/review/"+id).statusCode()).isIn(404,405);
        assertThat(ok(login(name).get(ROOT+"/mine")).toString()).contains("AWAITING_ASSIGNMENT").doesNotContain("identityNumber");
        assertThat(source).isPositive();
    }
    @Test void openTransferBlocksCheckoutAndReturnCanBeSupplemented() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var affairs=login("affairs1001");
        var name=housedStudent(admin,"dorm1001");long assignment=currentAssignment(name);
        var student=login(name);var created=ok(student.send("POST",ROOT+"/mine",draft()));
        var submitted=ok(student.send("POST",ROOT+"/mine/"+created.path("id").asLong()+"/submit",action(created.path("version").asInt(),"提交")));
        assertThat(manager.send("POST","/api/housing/assignments/"+assignment+"/checkout",Map.of("requestKey",key(),"version",0,"reason","调宿期间退宿")).statusCode()).isEqualTo(409);
        var returned=ok(affairs.send("POST",ROOT+"/review/"+created.path("id").asLong()+"/return",action(submitted.path("version").asInt(),"请补充意向楼层")));
        var again=draft();again.put("materialNote","已补充三年级证明");again.put("version",returned.path("version").asInt());again.put("requestKey",key());
        var supplemented=ok(student.send("POST",ROOT+"/mine/"+created.path("id").asLong()+"/supplement",again));
        assertThat(supplemented.path("status").asText()).isEqualTo("SUBMITTED");
        ok(student.send("POST",ROOT+"/mine/"+created.path("id").asLong()+"/withdraw",action(supplemented.path("version").asInt(),"不再调宿")));
        ok(manager.send("POST","/api/housing/assignments/"+assignment+"/checkout",Map.of("requestKey",key(),"version",0,"reason","撤回后退宿")));
    }
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
