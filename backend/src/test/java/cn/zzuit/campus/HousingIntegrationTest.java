package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
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
    "spring.datasource.url=jdbc:h2:mem:campus-housing-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class HousingIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/admin/housing";
    @Test void housingRequiresLoginCsrfAndSeparatePermissions() throws Exception {
        assertThat(new Browser().get(ROOT+"/catalog").statusCode()).isEqualTo(401);
        assertThat(new Browser().get("/api/housing/mine").statusCode()).isEqualTo(401);
        for(String name:List.of("20260001","teacher1001","dorm1001","dispatcher1001")) assertThat(login(name).get(ROOT+"/catalog").statusCode()).isEqualTo(403);
        var admin=login("admin");
        var raw=admin.http.send(admin.builder(ROOT+"/buildings").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(buildingFields(1)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(raw.statusCode()).isEqualTo(403);
        assertThat(login("teacher1001").get("/api/housing/mine").statusCode()).isEqualTo(403);
        assertThat(login("20260001").get("/api/housing/beds?buildingId=1").statusCode()).isEqualTo(403);
        assertThat(login("admin").send("POST","/api/housing/assignments",Map.of("requestKey",key(),"studentId",1,"bedId",1,"reason","管理员不能代替宿管入住")).statusCode()).isEqualTo(403);
    }
    @Test void catalogRejectsDuplicateAndImmutableIdentityAndKeepsHistory() throws Exception {
        var admin=login("admin");var fields=buildingFields(1);var building=ok(admin.send("POST",ROOT+"/buildings",fields));
        assertThat(admin.send("POST",ROOT+"/buildings",fields).statusCode()).isEqualTo(409);
        fields.put("name","修改后的演示宿舍楼");var changed=ok(admin.send("PUT",ROOT+"/buildings/"+building.path("id").asLong(),fields));
        assertThat(changed.path("version").asInt()).isEqualTo(1);
        fields.put("version",1);fields.put("campusId",2);assertThat(admin.send("PUT",ROOT+"/buildings/"+building.path("id").asLong(),fields).statusCode()).isEqualTo(409);
        var deleted=admin.http.send(admin.builder(ROOT+"/buildings/"+building.path("id").asLong()).header(admin.header,admin.token).DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(405);
        assertThat(ok(admin.get(ROOT+"/buildings?q="+building.path("code").asText())).path("total").asLong()).isEqualTo(1);
        var history=ok(admin.get(ROOT+"/history?type=BUILDING&id="+building.path("id").asLong()));
        assertThat(history.path("total").asLong()).isGreaterThanOrEqualTo(2);assertThat(history.toString()).contains("BUILDING_UPDATE","演示配置原因");
    }
    @Test void occupiedResourcesCannotBeDisabledAndMaintenanceBlocksAssignment() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var student=student();long studentId=user(student);
        var place=home(admin,"dorm1001");
        var stay=ok(manager.send("POST","/api/housing/assignments",checkIn(studentId,place.bed(),"新生入住")));
        assertThat(admin.send("POST",ROOT+"/buildings/"+place.building()+"/state",Map.of("enabled",false,"version",0,"reason","尝试停用在住楼栋")).statusCode()).isEqualTo(409);
        assertThat(admin.send("POST",ROOT+"/beds/"+place.bed()+"/maintenance",Map.of("status","MAINTENANCE","version",place.bedVersion()+1,"reason","尝试维护在住床位")).statusCode()).isEqualTo(409);
        ok(manager.send("POST","/api/housing/assignments/"+stay.path("id").asLong()+"/checkout",Map.of("requestKey",key(),"version",stay.path("version").asInt(),"reason","核对后退宿")));
        var bed=ok(admin.send("POST",ROOT+"/beds/"+place.bed()+"/maintenance",Map.of("status","MAINTENANCE","version",jdbc.sql("SELECT version FROM dorm_bed WHERE id=:id").param("id",place.bed()).query(Integer.class).single(),"reason","水管维修")));
        assertThat(manager.send("POST","/api/housing/assignments",checkIn(studentId,place.bed(),"维护期间入住")).statusCode()).isEqualTo(409);
        ok(admin.send("POST",ROOT+"/beds/"+place.bed()+"/maintenance",Map.of("status","VACANT","version",bed.path("version").asInt(),"reason","维修完成")));
        ok(admin.send("POST",ROOT+"/buildings/"+place.building()+"/state",Map.of("enabled",false,"version",0,"reason","空楼停用")));
        assertThat(manager.send("POST","/api/housing/assignments",checkIn(studentId,place.bed(),"停用后入住")).statusCode()).isEqualTo(404);
    }
    @Test void checkInIsPrivateIdempotentAndRejectsASecondCurrentStay() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var outsider=login(managerAccount());
        var first=student();var second=student();long firstId=user(first);long secondId=user(second);String secret=identity(first);
        var place=home(admin,"dorm1001");long otherBed=bed(admin,place.room(),"B");
        var otherBuilding=building(admin,2);long otherRoom=room(admin,otherBuilding);long foreignBed=bed(admin,otherRoom,"A");
        ok(admin.send("POST",ROOT+"/scopes",scopeFields(outsiderName(outsider),otherBuilding)));
        String token=key();var body=checkIn(firstId,place.bed(),"入学入住");body.put("requestKey",token);
        var stay=ok(manager.send("POST","/api/housing/assignments",body));
        assertThat(ok(manager.send("POST","/api/housing/assignments",body)).path("id").asLong()).isEqualTo(stay.path("id").asLong());
        body.put("studentId",secondId);assertThat(manager.send("POST","/api/housing/assignments",body).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE request_key=:k").param("k",token).query(Long.class).single()).isEqualTo(1);
        assertThat(manager.send("POST","/api/housing/assignments",checkIn(firstId,otherBed,"第二张床")).statusCode()).isEqualTo(409);
        assertThat(manager.send("POST","/api/housing/assignments",checkIn(secondId,place.bed(),"占用同一床")).statusCode()).isEqualTo(409);
        assertThat(manager.send("POST","/api/housing/assignments",checkIn(user("teacher1001"),otherBed,"教师入住")).statusCode()).isEqualTo(404);
        assertThat(manager.send("POST","/api/housing/assignments",checkIn(secondId,foreignBed,"跨楼入住")).statusCode()).isEqualTo(404);
        var mine=ok(login(first).get("/api/housing/mine"));
        assertThat(mine.path("current").path("bedLabel").asText()).isEqualTo("1 号床");assertThat(mine.toString()).doesNotContain(secret,"password","identityNumber",second);
        assertThat(ok(login(second).get("/api/housing/mine")).path("current").isNull()).isTrue();
        var beds=ok(manager.get("/api/housing/beds?buildingId="+place.building()+"&pageSize=100"));
        assertThat(beds.toString()).contains(first).doesNotContain(secret,"identityNumber");
        assertThat(outsider.get("/api/housing/beds?buildingId="+place.building()).statusCode()).isEqualTo(404);
        var notices=jdbc.sql("SELECT body FROM user_notice WHERE user_id=:u ORDER BY id DESC").param("u",firstId).query(String.class).list();
        assertThat(notices.get(0)).contains("已为你登记入住").doesNotContain(secret,second);
    }
    @Test void checkoutReleasesTheBedAndLaterRenamesDoNotRewriteHistory() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var name=student();long studentId=user(name);var place=home(admin,"dorm1001");
        var stay=ok(manager.send("POST","/api/housing/assignments",checkIn(studentId,place.bed(),"入住")));
        var renamed=buildingFields(1);renamed.put("code",jdbc.sql("SELECT code FROM dorm_building WHERE id=:id").param("id",place.building()).query(String.class).single());renamed.put("name","退宿前名称");renamed.put("version",0);
        ok(admin.send("PUT",ROOT+"/buildings/"+place.building(),renamed));
        assertThat(ok(login(name).get("/api/housing/mine")).path("current").path("buildingName").asText()).isEqualTo("退宿前名称");
        String checkout=key();var ended=ok(manager.send("POST","/api/housing/assignments/"+stay.path("id").asLong()+"/checkout",Map.of("requestKey",checkout,"version",stay.path("version").asInt(),"reason","学期结束")));
        assertThat(ok(manager.send("POST","/api/housing/assignments/"+stay.path("id").asLong()+"/checkout",Map.of("requestKey",checkout,"version",0,"reason","学期结束"))).path("status").asText()).isEqualTo("ENDED");
        assertThat(manager.send("POST","/api/housing/assignments/"+stay.path("id").asLong()+"/checkout",Map.of("requestKey",checkout,"version",0,"reason","另一原因")).statusCode()).isEqualTo(409);
        renamed.put("name","退宿后改名");renamed.put("version",1);ok(admin.send("PUT",ROOT+"/buildings/"+place.building(),renamed));
        var history=ok(login(name).get("/api/housing/mine")).path("history");
        assertThat(history.get(0).path("buildingName").asText()).isEqualTo("退宿前名称");assertThat(history.get(0).path("endReason").asText()).isEqualTo("学期结束");
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",place.bed()).query(String.class).single()).isEqualTo("VACANT");
        assertThat(ended.path("status").asText()).isEqualTo("ENDED");
        ok(manager.send("POST","/api/housing/assignments",checkIn(studentId,place.bed(),"再次入住")));
    }
    @Test void concurrentCheckInAndCheckoutAllowOnlyOneWinner() throws Exception {
        var admin=login("admin");var manager=login("dorm1001");var place=home(admin,"dorm1001");
        long one=user(student());long two=user(student());
        var first=CompletableFuture.supplyAsync(()->sendUnchecked(manager,"POST","/api/housing/assignments",checkIn(one,place.bed(),"并发甲")));
        var second=CompletableFuture.supplyAsync(()->sendUnchecked(manager,"POST","/api/housing/assignments",checkIn(two,place.bed(),"并发乙")));
        assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE current_bed_id=:id").param("id",place.bed()).query(Long.class).single()).isEqualTo(1);
        long stay=jdbc.sql("SELECT id FROM housing_assignment WHERE current_bed_id=:id").param("id",place.bed()).query(Long.class).single();
        int version=jdbc.sql("SELECT version FROM housing_assignment WHERE id=:id").param("id",stay).query(Integer.class).single();
        var left=CompletableFuture.supplyAsync(()->sendUnchecked(manager,"POST","/api/housing/assignments/"+stay+"/checkout",Map.of("requestKey",key(),"version",version,"reason","并发退宿甲")));
        var right=CompletableFuture.supplyAsync(()->sendUnchecked(manager,"POST","/api/housing/assignments/"+stay+"/checkout",Map.of("requestKey",key(),"version",version,"reason","并发退宿乙")));
        assertThat(List.of(left.get().statusCode(),right.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",place.bed()).query(String.class).single()).isEqualTo("VACANT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE bed_id=:id AND status='CURRENT'").param("id",place.bed()).query(Long.class).single()).isZero();
    }
    @Test void revokedRoleMakesScopeIneffectiveWithoutRestoringIt() throws Exception {
        var admin=login("admin");var place=home(admin,"dorm1001");long id=user("dorm1001");
        jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='DORM_MANAGER'").param("u",id).update();
        try {
            assertThat(ok(admin.get(ROOT+"/scopes?pageSize=100")).toString()).contains("对应岗位权限已移除");
            assertThat(login("dorm1001").get("/api/housing/responsibilities").statusCode()).isEqualTo(403);
        } finally {jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'DORM_MANAGER')").param("u",id).update();}
        assertThat(login("dorm1001").get("/api/housing/responsibilities").statusCode()).isEqualTo(200);
        assertThat(place.bed()).isPositive();
    }
    @Test void searchEscapesWildcardsAndInvalidPagesDoNotWriteAudit() throws Exception {
        var admin=login("admin");var fields=buildingFields(1);fields.put("name","百分号%宿舍");ok(admin.send("POST",ROOT+"/buildings",fields));
        assertThat(ok(admin.get(ROOT+"/buildings?q="+URLEncoder.encode("%",java.nio.charset.StandardCharsets.UTF_8))).path("total").asLong()).isEqualTo(1);
        long before=jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='DORM_BUILDING'").query(Long.class).single();
        fields.put("reason","x".repeat(201));assertThat(admin.send("POST",ROOT+"/buildings",fields).statusCode()).isEqualTo(400);
        assertThat(admin.get(ROOT+"/buildings?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(admin.get(ROOT+"/history?type=OTHER&id=1").statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='DORM_BUILDING'").query(Long.class).single()).isEqualTo(before);
    }
    private Place home(Browser admin,String manager)throws Exception{long building=building(admin,1);long room=room(admin,building);long bed=bed(admin,room,"A");ok(admin.send("POST",ROOT+"/scopes",scopeFields(manager,building)));return new Place(building,room,bed,0);}
    private long building(Browser admin,long campus)throws Exception{return ok(admin.send("POST",ROOT+"/buildings",buildingFields(campus))).path("id").asLong();}
    private long room(Browser admin,long building)throws Exception{var fields=new HashMap<String,Object>(Map.of("buildingId",building,"code","R-"+UUID.randomUUID().toString().substring(0,8),"name","101","floorLabel","1 层","version",0,"reason","演示配置原因"));return ok(admin.send("POST",ROOT+"/rooms",fields)).path("id").asLong();}
    private long bed(Browser admin,long room,String code)throws Exception{return ok(admin.send("POST",ROOT+"/beds",Map.of("roomId",room,"code",code,"label","1 号床","version",0,"reason","演示配置原因"))).path("id").asLong();}
    private HashMap<String,Object> buildingFields(long campus){return new HashMap<>(Map.of("campusId",campus,"code","DORM-"+UUID.randomUUID().toString().substring(0,8),"name","演示宿舍楼","provenance","DEMO","version",0,"reason","演示配置原因"));}
    private HashMap<String,Object> checkIn(long student,long bed,String reason){return new HashMap<>(Map.of("requestKey",key(),"studentId",student,"bedId",bed,"reason",reason));}
    private Map<String,Object> scopeFields(String username,long building){return Map.of("userId",user(username),"buildingId",building,"version",0,"reason","演示范围原因");}
    private String key(){return "HK"+UUID.randomUUID().toString().replace("-","");}
    private String student(){return account("hs","住宿测试学生","20260001","STUDENT");}
    private String managerAccount(){return account("hm","测试宿管","dorm1001","DORM_MANAGER");}
    private String account(String prefix,String label,String source,String role){String name=prefix+UUID.randomUUID().toString().replace("-","").substring(0,12);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :u,password_hash,:n,:num,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username=:source").param("u",name).param("n",label).param("num",prefix.toUpperCase()+"-"+name).param("source",source).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",user(name)).param("role",role).update();return name;}
    private String outsiderName(Browser ignored){return jdbc.sql("SELECT username FROM app_user WHERE full_name='测试宿管' ORDER BY id DESC").query(String.class).list().get(0);}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private String identity(String name){return jdbc.sql("SELECT identity_number FROM app_user WHERE username=:u").param("u",name).query(String.class).single();}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private HttpResponse<String> sendUnchecked(Browser actor,String method,String path,Map<String,?> body){try{return actor.send(method,path,body);}catch(Exception ex){throw new RuntimeException(ex);}}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private record Place(long building,long room,long bed,int bedVersion) {}
    private class Browser{
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
