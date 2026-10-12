package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.bootstrap.RepairDemoInitializer;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.repair.RepairSettingsService;
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
    "spring.datasource.url=jdbc:h2:mem:campus-repair-settings-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class RepairSettingsIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired IdentityService identities;
    @Autowired RepairSettingsService service;
    @Autowired RepairDemoInitializer initializer;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/admin/repair-settings";
    @Test void settingsRequireLoginCsrfAndSpecificManagementPermission() throws Exception {
        assertThat(new Browser().get(ROOT+"/catalog").statusCode()).isEqualTo(401);
        for(String name:List.of("20260001","teacher1001","staff1001","dispatcher1001","technician1001"))assertThat(login(name).get(ROOT+"/catalog").statusCode()).isEqualTo(403);
        var admin=login("admin");
        var raw=admin.http.send(admin.builder(ROOT+"/locations").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(publicFields(1)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(raw.statusCode()).isEqualTo(403);
    }
    @Test void publicLocationCatalogIsMinimalAndForStudentsOrTeachersOnly() throws Exception {
        var place=create(login("admin"),publicFields(1));
        for(String name:List.of("20260001","teacher1001")) {
            var actor=login(name);var rows=ok(actor.get("/api/repairs/locations?pageSize=100"));
            assertThat(rows.toString()).contains(place.path("name").asText()).doesNotContain("people","classroomResourceId","buildingId","reason","userId","password","identityNumber");
            assertThat(ok(actor.get("/api/repairs/catalog")).path("categories").size()).isEqualTo(7);
        }
        assertThat(login("teacher1001").get("/api/portal/overview").statusCode()).isEqualTo(403);
        assertThat(login("technician1001").get("/api/repairs/catalog").statusCode()).isEqualTo(403);
    }
    @Test void sourceClassroomIsExplicitAndDuplicateBindingsAreRejected() throws Exception {
        var admin=login("admin");long room=room(1,true);var fields=classroomFields(1,room);
        var place=create(admin,fields);assertThat(place.path("available").asBoolean()).isTrue();assertThat(place.path("buildingId").isNull()).isTrue();
        assertThat(admin.send("POST",ROOT+"/locations",classroomFields(1,room)).statusCode()).isEqualTo(409);
    }
    @Test void invalidAndCrossCampusSourcesCannotBeConfigured() throws Exception {
        var admin=login("admin");long room=room(2,true);
        assertThat(admin.send("POST",ROOT+"/locations",classroomFields(1,room)).statusCode()).isEqualTo(400);
        assertThat(admin.send("POST",ROOT+"/locations",classroomFields(1,999999L)).statusCode()).isEqualTo(400);
        var fields=publicFields(1);fields.put("kind","DORMITORY");assertThat(admin.send("POST",ROOT+"/locations",fields).statusCode()).isEqualTo(400);
        fields=publicFields(1);fields.put("classroomResourceId",room);assertThat(admin.send("POST",ROOT+"/locations",fields).statusCode()).isEqualTo(400);
    }
    @Test void buildingCommonRequiresAnExplicitSameCampusBuilding() throws Exception {
        var admin=login("admin");String code="TEST-"+UUID.randomUUID();jdbc.sql("INSERT INTO building(campus_id,code,name) VALUES(2,:c,'测试演示楼栋')").param("c",code).update();
        long building=jdbc.sql("SELECT id FROM building WHERE code=:c").param("c",code).query(Long.class).single();
        var fields=publicFields(1);fields.put("kind","BUILDING_COMMON");assertThat(admin.send("POST",ROOT+"/locations",fields).statusCode()).isEqualTo(400);
        fields.put("buildingId",building);assertThat(admin.send("POST",ROOT+"/locations",fields).statusCode()).isEqualTo(400);
        fields.put("campusId",2);var place=create(admin,fields);assertThat(place.path("buildingVerificationStatus").asText()).isEqualTo("PENDING");
    }
    @Test void stoppingOrMovingSourceRemovesPublicAvailabilityImmediately() throws Exception {
        var admin=login("admin");long room=room(1,true);var place=create(admin,classroomFields(1,room));long id=place.path("id").asLong();
        jdbc.sql("UPDATE classroom_resource SET enabled=FALSE WHERE id=:id").param("id",room).update();
        assertThat(ok(login("20260001").get("/api/repairs/locations?pageSize=100")).path("items").toString()).doesNotContain("\"id\":"+id+",");
        var state=Map.<String,Object>of("enabled",false,"version",0,"reason","暂时关闭");ok(admin.send("POST",ROOT+"/locations/"+id+"/state",state));
        assertThat(admin.send("POST",ROOT+"/locations/"+id+"/state",Map.of("enabled",true,"version",1,"reason","尝试开启")).statusCode()).isEqualTo(400);
        jdbc.sql("UPDATE classroom_resource SET enabled=TRUE,campus_id=2 WHERE id=:id").param("id",room).update();
        assertThat(admin.send("POST",ROOT+"/locations/"+id+"/state",Map.of("enabled",true,"version",1,"reason","尝试跨校区开启")).statusCode()).isEqualTo(400);
    }
    @Test void locationIdentityIsImmutableAndHistoryHasReasonAndActor() throws Exception {
        var admin=login("admin");var fields=publicFields(1);var place=create(admin,fields);long id=place.path("id").asLong();
        fields.put("name","修改后的演示公共地点");var changed=ok(admin.send("PUT",ROOT+"/locations/"+id,fields));assertThat(changed.path("version").asInt()).isEqualTo(1);
        assertThat(admin.send("PUT",ROOT+"/locations/"+id,fields).statusCode()).isEqualTo(409);
        fields.put("version",1);fields.put("campusId",2);assertThat(admin.send("PUT",ROOT+"/locations/"+id,fields).statusCode()).isEqualTo(409);
        var history=ok(admin.get(ROOT+"/history?type=LOCATION&id="+id));assertThat(history.path("total").asLong()).isEqualTo(2);assertThat(history.toString()).contains("LOCATION_UPDATE","演示配置原因","actorName");
        assertThat(ok(admin.get("/api/admin/foundation/audit?entityType=REPAIR_LOCATION&entityId="+id)).path("total").asLong()).isEqualTo(2);
    }
    @Test void scopePersonMustHaveCorrectEnabledRoleAndLocationCampus() throws Exception {
        var admin=login("admin");long place=create(admin,publicFields(2)).path("id").asLong();
        assertThat(admin.send("POST",ROOT+"/scopes",scopeFields("20260001","WORK",2,place)).statusCode()).isEqualTo(400);
        assertThat(admin.send("POST",ROOT+"/scopes",scopeFields("technician1001","DISPATCH",2,place)).statusCode()).isEqualTo(400);
        assertThat(admin.send("POST",ROOT+"/scopes",scopeFields("technician1001","WORK",1,place)).statusCode()).isEqualTo(400);
    }
    @Test void scopedMatchingDoesNotGrantOtherLocationsCategoriesOrCampuses() throws Exception {
        var admin=login("admin");long place=create(admin,publicFields(2)).path("id").asLong(),other=create(admin,publicFields(2)).path("id").asLong();
        var fields=scopeFields("technician1001","WORK",2,place);var scope=ok(admin.send("POST",ROOT+"/scopes",fields));
        var person=(CampusPrincipal)identities.loadUserByUsername("technician1001");
        assertThat(service.matches(person,"WORK",2,"WATER",place)).isTrue();assertThat(service.matches(person,"WORK",2,"WATER",other)).isFalse();
        assertThat(service.matches(person,"WORK",2,"NETWORK",place)).isFalse();assertThat(service.matches(person,"WORK",1,"WATER",place)).isFalse();
        assertThat(admin.send("POST",ROOT+"/scopes",fields).statusCode()).isEqualTo(409);
        ok(admin.send("POST",ROOT+"/scopes/"+scope.path("id").asLong()+"/state",Map.of("enabled",false,"version",0,"reason","撤销负责范围")));
        assertThat(service.matches(person,"WORK",2,"WATER",place)).isFalse();
    }
    @Test void responsibilitiesNeverExposeOtherPeopleAndChangesTakeEffectWithoutNewLogin() throws Exception {
        var admin=login("admin");var worker=login("technician1001");long place=create(admin,publicFields(2)).path("id").asLong();
        var scope=ok(admin.send("POST",ROOT+"/scopes",scopeFields("technician1001","WORK",2,place)));long id=scope.path("id").asLong();
        var mine=ok(worker.get("/api/repairs/responsibilities"));assertThat(mine.toString()).doesNotContain("后勤派单人员","DISPATCH","password","identityNumber");
        ok(admin.send("POST",ROOT+"/scopes/"+id+"/state",Map.of("enabled",false,"version",0,"reason","撤销范围")));
        var updated=ok(worker.get("/api/repairs/responsibilities"));assertThat(updated.path("scopes").toString()).contains("范围已停用");
        assertThat(login("20260001").get("/api/repairs/responsibilities").statusCode()).isEqualTo(403);
    }
    @Test void revokedRoleMakesExistingScopeIneffectiveAndBootstrapDoesNotRestoreIt() throws Exception {
        var admin=login("admin");long id=user("technician1001");jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='REPAIR_TECHNICIAN'").param("u",id).update();
        try {initializer.run(null);assertThat(ok(admin.get(ROOT+"/scopes?pageSize=100")).toString()).contains("对应岗位权限已移除");assertThat(login("technician1001").get("/api/repairs/responsibilities").statusCode()).isEqualTo(403);}
        finally {jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'REPAIR_TECHNICIAN')").param("u",id).update();}
    }
    @Test void concurrentConfigurationUpdateHasOneWinnerAndOneAudit() throws Exception {
        var admin=login("admin");var fields=publicFields(1);long id=create(admin,fields).path("id").asLong();fields.put("name","并发修改演示地点");
        var first=CompletableFuture.supplyAsync(()->sendUnchecked(admin,"PUT",ROOT+"/locations/"+id,fields));var second=CompletableFuture.supplyAsync(()->sendUnchecked(admin,"PUT",ROOT+"/locations/"+id,fields));
        assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(ok(admin.get(ROOT+"/history?type=LOCATION&id="+id)).path("total").asLong()).isEqualTo(2);
    }
    @Test void invalidPaginationAndExcessReasonFailWithoutAuditChanges() throws Exception {
        var admin=login("admin");long before=jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='REPAIR_LOCATION'").query(Long.class).single();
        var fields=publicFields(1);fields.put("reason","x".repeat(201));assertThat(admin.send("POST",ROOT+"/locations",fields).statusCode()).isEqualTo(400);
        assertThat(admin.get(ROOT+"/locations?pageSize=101").statusCode()).isEqualTo(400);assertThat(admin.get(ROOT+"/history?type=OTHER&id=1").statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='REPAIR_LOCATION'").query(Long.class).single()).isEqualTo(before);
    }
    @Test void sourceSearchEscapesWildcardCharactersInsteadOfMatchingEverything() throws Exception {
        var admin=login("admin");var fields=publicFields(1);fields.put("name","包含百分号%的演示地点");create(admin,fields);
        assertThat(ok(admin.get(ROOT+"/locations?q="+URLEncoder.encode("%",java.nio.charset.StandardCharsets.UTF_8))).path("total").asLong()).isEqualTo(1);
    }
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private long room(long campus,boolean enabled){String code="REPAIR-TEST-"+UUID.randomUUID();jdbc.sql("INSERT INTO classroom_resource(campus_id,code,name,building_name,room_number,teaching_capacity,exam_capacity,enabled,origin) VALUES(:c,:code,:name,'演示教学楼',:number,50,30,:s,'DEMO')").param("c",campus).param("code",code).param("name",code).param("number",code).param("s",enabled).update();return jdbc.sql("SELECT id FROM classroom_resource WHERE code=:c").param("c",code).query(Long.class).single();}
    private HashMap<String,Object> publicFields(long campus){return new HashMap<>(Map.of("campusId",campus,"code","TEST-"+UUID.randomUUID(),"name","报修测试演示地点","kind","CAMPUS_PUBLIC","detailHint","仅用于独立测试","provenance","DEMO","version",0,"reason","演示配置原因"));}
    private HashMap<String,Object> classroomFields(long campus,long room){var fields=publicFields(campus);fields.put("kind","CLASSROOM");fields.put("classroomResourceId",room);return fields;}
    private HashMap<String,Object> scopeFields(String name,String capability,long campus,Long place){var fields=new HashMap<String,Object>(Map.of("userId",user(name),"capability",capability,"campusId",campus,"category","WATER","version",0,"reason","演示范围原因"));if(place!=null)fields.put("locationId",place);return fields;}
    private JsonNode create(Browser actor,Map<String,?> fields)throws Exception{return ok(actor.send("POST",ROOT+"/locations",fields));}
    private HttpResponse<String> sendUnchecked(Browser actor,String method,String path,Map<String,?> body){try{return actor.send(method,path,body);}catch(Exception ex){throw new RuntimeException(ex);}}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private class Browser{
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
