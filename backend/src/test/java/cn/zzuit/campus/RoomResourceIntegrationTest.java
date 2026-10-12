package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class RoomResourceIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/admin/resources";

    @Test void onlyAdministratorsMaintainRoomsAndAcademicStaffReadSchedulingCatalog() throws Exception {
        assertThat(new Browser().get(ROOT).statusCode()).isEqualTo(401);
        for(String name:List.of("20260001","teacher1001","academic1001","editor","reviewer","staff1001")) {
            var user=login(name); assertThat(user.get(ROOT).statusCode()).isEqualTo(403);
            assertThat(user.send("POST",ROOT,fields("DENIED-"+UUID.randomUUID(),0,20,10)).statusCode()).isEqualTo(403);
        }
        assertThat(login("admin").get(ROOT+"/catalog").statusCode()).isEqualTo(200);
        assertThat(login("admin").get("/api/academic/catalog").statusCode()).isEqualTo(403);
        assertThat(login("academic1001").tree("/api/academic/catalog").path("rooms").isArray()).isTrue();
    }

    @Test void roomFieldsFiltersVersionsAndAuditAreEnforced() throws Exception {
        var admin=login("admin"); var room=create(admin,20,10); long id=id(room); String name=room.path("name").asText();
        var page=admin.tree(ROOT+"?q="+room.path("code").asText()+"&campusId=1&type=LECTURE&enabled=true&ready=true&page=1&pageSize=1");
        assertThat(page.path("total").asLong()).isEqualTo(1); assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(id);
        var input=fields(room.path("code").asText(),0,30,15); input.put("name",name+"-更名"); input.put("buildingName",room.path("buildingName").asText()); input.put("roomNumber",room.path("roomNumber").asText());
        var updated=ok(admin.send("PUT",ROOT+"/"+id,input)); assertThat(updated.path("version").asInt()).isEqualTo(1);
        assertThat(admin.send("PUT",ROOT+"/"+id,input).statusCode()).isEqualTo(409);
        input.put("version",1); input.put("reason","  "); assertThat(admin.send("PUT",ROOT+"/"+id,input).statusCode()).isEqualTo(400);
        var history=admin.tree(ROOT+"/"+id+"/history?page=1&pageSize=20"); assertThat(history.path("total").asInt()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("actorName").asText()).isEqualTo("管理员（演示）");
        assertThat(history.path("items").get(0).path("detail").asText()).contains(name,name+"-更名");
        assertThat(admin.get(ROOT+"?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(admin.get(ROOT+"?page=0").statusCode()).isEqualTo(400);
        assertThat(admin.get(ROOT+"?type=INVALID").statusCode()).isEqualTo(400);
    }

    @Test void invalidAndDuplicateRoomsDoNotProducePartialRecords() throws Exception {
        var admin=login("admin"); var room=create(admin,20,10); long before=count("SELECT COUNT(*) FROM room_audit");
        var input=fields(room.path("code").asText(),0,20,10); assertThat(admin.send("POST",ROOT,input).statusCode()).isEqualTo(409);
        for(int capacity:List.of(0,1001)) { input=fields("BAD-"+UUID.randomUUID(),0,capacity,1); assertThat(admin.send("POST",ROOT,input).statusCode()).isEqualTo(400); }
        input=fields("BAD-"+UUID.randomUUID(),0,10,11); assertThat(admin.send("POST",ROOT,input).statusCode()).isEqualTo(400);
        input=fields("BAD-"+UUID.randomUUID(),0,10,5); input.put("campusId",999999); assertThat(admin.send("POST",ROOT,input).statusCode()).isEqualTo(400);
        input=fields("BAD-"+UUID.randomUUID(),0,10,5); input.put("buildingName","  "); assertThat(admin.send("POST",ROOT,input).statusCode()).isEqualTo(400);
        input=fields("NEW-"+UUID.randomUUID(),0,10,5); input.put("buildingName",room.path("buildingName").asText()); input.put("roomNumber",room.path("roomNumber").asText());
        assertThat(admin.send("POST",ROOT,input).statusCode()).isEqualTo(409); assertThat(count("SELECT COUNT(*) FROM room_audit")).isEqualTo(before);
    }

    @Test void schedulingRequiresARegisteredEnabledCompleteRoomAndDerivesItsCampus() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); long group=group(academic,"2031",false); var room=create(admin,20,10); long id=id(room);
        assertThat(lesson(academic,group,"teacher1001",999999,"2031-04-01").statusCode()).isEqualTo(404);
        assertThat(academic.send("POST","/api/academic/classes/"+group+"/lessons",Map.of("teacherId",user("teacher1001"),"campusId",1,"classroom","任意文字","startsAt","2031-04-01T08:00:00","endsAt","2031-04-01T09:00:00")).statusCode()).isEqualTo(400);
        ok(admin.send("POST",ROOT+"/"+id+"/state",Map.of("enabled",false,"version",0,"reason","暂时关闭")));
        assertThat(lesson(academic,group,"teacher1001",id,"2031-04-01").statusCode()).isEqualTo(409);
        var incomplete=create(admin,20,10); jdbc.sql("UPDATE classroom_resource SET teaching_capacity=NULL WHERE id=:id").param("id",id(incomplete)).update();
        assertThat(lesson(academic,group,"teacher1001",id(incomplete),"2031-04-01").statusCode()).isEqualTo(409);
        var input=fields("WEST-"+UUID.randomUUID(),0,20,10); input.put("campusId",2); var west=ok(admin.send("POST",ROOT,input));
        var response=academic.send("POST","/api/academic/classes/"+group+"/lessons",Map.of("teacherId",user("teacher1001"),"roomId",id(west),"campusId",1,"classroom","伪造地点","startsAt","2031-04-01T08:00:00","endsAt","2031-04-01T09:00:00"));
        ok(response); assertThat(jdbc.sql("SELECT campus_id FROM teaching_lesson WHERE class_id=:c").param("c",group).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT classroom FROM teaching_lesson WHERE class_id=:c").param("c",group).query(String.class).single()).isEqualTo(west.path("name").asText());
    }

    @Test void roomRenamingCannotBypassOccupancyAndOldDisplayIsPreserved() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,20,10); long id=id(room);
        long first=group(academic,"2032",false), second=group(academic,"2032",true); ok(lesson(academic,first,"teacher1001",id,"2032-04-01"));
        var input=fields(room.path("code").asText(),0,20,10); input.put("name",room.path("name").asText()+"-新名称"); input.put("buildingName",room.path("buildingName").asText()); input.put("roomNumber",room.path("roomNumber").asText());
        ok(admin.send("PUT",ROOT+"/"+id,input)); assertThat(lesson(academic,second,"teacher1002",id,"2032-04-01").statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT classroom FROM teaching_lesson WHERE class_id=:c").param("c",first).query(String.class).single()).isEqualTo(room.path("name").asText());
        input.put("version",1); input.put("campusId",2); assertThat(admin.send("PUT",ROOT+"/"+id,input).statusCode()).isEqualTo(409);
        input.put("campusId",1); input.put("roomNumber","移动后"); assertThat(admin.send("PUT",ROOT+"/"+id,input).statusCode()).isEqualTo(409);
    }

    @Test void teachingCapacityRejectsOversizedClassAndNewMembership() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,1,1); long group=group(academic,"2033",false);
        ok(lesson(academic,group,"teacher1001",id(room),"2033-04-01"));
        assertThat(academic.send("POST","/api/academic/classes/"+group+"/members",Map.of("userId",user("20260002"),"active",true)).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM teaching_member WHERE class_id=:c AND active=TRUE").param("c",group).query(Long.class).single()).isEqualTo(1);
        long oversized=group(academic,"2033",true); ok(academic.send("POST","/api/academic/classes/"+oversized+"/members",Map.of("userId",user("20260001"),"active",true)));
        assertThat(lesson(academic,oversized,"teacher1002",id(room),"2033-04-02").statusCode()).isEqualTo(409);
        assertThat(exam(academic,oversized,id(room),"2033-04-03").statusCode()).isEqualTo(409);
    }

    @Test void examPublicationRechecksSeatsAfterMembershipChangesAndRoomDisabling() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,5,1); long group=group(academic,"2034",false);
        var exam=ok(exam(academic,group,id(room),"2034-04-01"));
        ok(academic.send("POST","/api/academic/classes/"+group+"/members",Map.of("userId",user("20260002"),"active",true)));
        assertThat(publish(academic,exam).statusCode()).isEqualTo(409);
        ok(admin.send("POST",ROOT+"/"+id(room)+"/state",Map.of("enabled",false,"version",0,"reason","草稿不占用，可停用")));
        assertThat(publish(academic,exam).statusCode()).isEqualTo(409);
        assertThat(academic.tree("/api/academic/classes/"+group).path("exams").get(0).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test void futureBookingsProtectRoomStateAndCapacityButPastBookingsRemainReadable() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,10,5); long group=group(academic,"2035",false);
        ok(academic.send("POST","/api/academic/classes/"+group+"/members",Map.of("userId",user("20260002"),"active",true)));
        var exam=ok(exam(academic,group,id(room),"2035-04-01")); ok(publish(academic,exam));
        assertThat(admin.send("POST",ROOT+"/"+id(room)+"/state",Map.of("enabled",false,"version",0,"reason","存在已发布考试" )).statusCode()).isEqualTo(409);
        var input=fields(room.path("code").asText(),0,10,1); input.put("name",room.path("name").asText()); input.put("buildingName",room.path("buildingName").asText()); input.put("roomNumber",room.path("roomNumber").asText());
        assertThat(admin.send("PUT",ROOT+"/"+id(room),input).statusCode()).isEqualTo(409);
        assertThat(academic.send("POST","/api/academic/classes/"+group+"/members",Map.of("userId",user("20260001"),"active",true)).statusCode()).isEqualTo(200);
        ok(academic.send("POST","/api/academic/exams/"+id(exam)+"/state",Map.of("status","DRAFT","version",1)));
        ok(admin.send("POST",ROOT+"/"+id(room)+"/state",Map.of("enabled",false,"version",0,"reason","考试已撤下")));
        var past=create(admin,10,5); long oldGroup=group(academic,"2020",false); ok(lesson(academic,oldGroup,"teacher1001",id(past),"2020-04-01"));
        ok(admin.send("POST",ROOT+"/"+id(past)+"/state",Map.of("enabled",false,"version",0,"reason","历史记录保留，关闭新排课")));
        assertThat(admin.tree(ROOT+"/"+id(past)).path("referenced").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM teaching_lesson WHERE class_id=:c").param("c",oldGroup).query(Long.class).single()).isEqualTo(1);
    }

    @Test void publishedExamSeatsProtectStudentActivation() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,5,1); long group=group(academic,"2036",false);
        ok(publish(academic,ok(exam(academic,group,id(room),"2036-04-01"))));
        assertThat(academic.send("POST","/api/academic/classes/"+group+"/members",Map.of("userId",user("20260002"),"active",true)).statusCode()).isEqualTo(409);
    }

    @Test void occupancyAndAuditArePagedWithoutStudentPersonalData() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,20,10); long group=group(academic,"2037",false);
        ok(lesson(academic,group,"teacher1001",id(room),"2037-04-01")); ok(publish(academic,ok(exam(academic,group,id(room),"2037-04-02"))));
        var page=admin.tree(ROOT+"/"+id(room)+"/occupancy?page=1&pageSize=1"); assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("kind").asText()).isEqualTo("LESSON"); assertThat(page.toString()).doesNotContain("identityNumber","studentName","password");
        assertThat(admin.tree(ROOT+"/"+id(room)).path("upcomingLessonCount").asInt()).isEqualTo(1);
        assertThat(admin.get(ROOT+"/"+id(room)+"/history?pageSize=101").statusCode()).isEqualTo(400);
    }

    @Test void disablingAndSchedulingCannotBothCommit() throws Exception {
        var admin=login("admin"); var academic=login("academic1001"); var room=create(admin,20,10); long group=group(academic,"2038",false);
        var one=CompletableFuture.supplyAsync(()->unchecked(()->admin.send("POST",ROOT+"/"+id(room)+"/state",Map.of("enabled",false,"version",0,"reason","并发停用")).statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->lesson(academic,group,"teacher1001",id(room),"2038-04-01").statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
        boolean enabled=admin.tree(ROOT+"/"+id(room)).path("enabled").asBoolean(); long lessons=jdbc.sql("SELECT COUNT(*) FROM teaching_lesson WHERE room_id=:id").param("id",id(room)).query(Long.class).single();
        assertThat(enabled || lessons==0).isTrue();
    }

    @Test void concurrentRoomEditsRetainOneVersionAndOneAudit() throws Exception {
        var admin=login("admin"); var other=login("admin"); var room=create(admin,20,10);
        var input=fields(room.path("code").asText(),0,30,15); input.put("name",room.path("name").asText()); input.put("buildingName",room.path("buildingName").asText()); input.put("roomNumber",room.path("roomNumber").asText());
        var one=CompletableFuture.supplyAsync(()->unchecked(()->admin.send("PUT",ROOT+"/"+id(room),input).statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->other.send("PUT",ROOT+"/"+id(room),input).statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
        assertThat(admin.tree(ROOT+"/"+id(room)+"/history").path("total").asInt()).isEqualTo(2);
    }

    @Test void migrationPreservesDistinctLegacyLocationsWithoutInventingMetadata() {
        String url="jdbc:h2:mem:room-migration-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(var source=new HikariDataSource()) {
        source.setJdbcUrl(url); source.setUsername("sa"); source.setPassword(""); source.setMaximumPoolSize(3);
        var legacy=JdbcClient.create(source);
        Flyway.configure().dataSource(source).target("7").load().migrate();
        legacy.sql("INSERT INTO app_user(id,username,password_hash,full_name,identity_number) VALUES(99,'migration-user','unused-fixture','迁移测试','M99')").update();
        legacy.sql("INSERT INTO academic_term(id,name,starts_on,ends_on) VALUES(99,'迁移学期','2020-01-01','2020-12-31')").update();
        legacy.sql("INSERT INTO course(id,code,name,credits) VALUES(99,'M99','迁移课程',1)").update();
        legacy.sql("INSERT INTO teaching_class(id,term_id,course_id,department_id,name) VALUES(99,99,99,1,'迁移班')").update();
        legacy.sql("INSERT INTO teaching_instructor(class_id,teacher_id) VALUES(99,99)").update();
        legacy.sql("INSERT INTO teaching_lesson(class_id,teacher_id,campus_id,classroom,starts_at,ends_at) VALUES(99,99,1,'旧教室 A-201','2020-04-01 08:00:00','2020-04-01 09:00:00')").update();
        legacy.sql("INSERT INTO academic_exam(class_id,campus_id,classroom,starts_at,ends_at,status) VALUES(99,1,'旧考场 A-201','2020-04-02 08:00:00','2020-04-02 09:00:00','PUBLISHED')").update();
        Flyway.configure().dataSource(source).target("8").load().migrate();
        assertThat(legacy.sql("SELECT COUNT(*) FROM classroom_resource WHERE building_name IS NULL AND teaching_capacity IS NULL AND exam_capacity IS NULL").query(Long.class).single()).isEqualTo(2);
        assertThat(legacy.sql("SELECT classroom FROM teaching_lesson WHERE class_id=99").query(String.class).single()).isEqualTo("旧教室 A-201");
        assertThat(legacy.sql("SELECT COUNT(*) FROM teaching_lesson WHERE room_id IS NOT NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(legacy.sql("SELECT COUNT(*) FROM academic_exam WHERE room_id IS NOT NULL AND status='PUBLISHED'").query(Long.class).single()).isEqualTo(1);
        }
    }

    private JsonNode create(Browser admin,int teaching,int exam) throws Exception { return ok(admin.send("POST",ROOT,fields("ROOM-"+UUID.randomUUID(),0,teaching,exam))); }
    private Map<String,Object> fields(String code,int version,int teaching,int exam) { var result=new HashMap<String,Object>(); result.put("campusId",1); result.put("code",code); result.put("name","演示教室-"+code); result.put("buildingName","演示楼-"+code); result.put("roomNumber","201"); result.put("floorLabel","二层"); result.put("type","LECTURE"); result.put("teachingCapacity",teaching); result.put("examCapacity",exam); result.put("version",version); result.put("reason","资源回归校核"); return result; }
    private long group(Browser academic,String year,boolean second) throws Exception {
        long term=id(ok(academic.send("POST","/api/academic/terms",Map.of("name","资源学期-"+UUID.randomUUID(),"startsOn",year+"-01-01","endsOn",year+"-12-31"))));
        return ok(academic.send("POST","/api/academic/classes",Map.of("termId",term,"courseId",1,"name","资源教学班-"+UUID.randomUUID(),"teacherIds",List.of(user(second?"teacher1002":"teacher1001")),"studentIds",List.of(user(second?"20260002":"20260001"))))).path("info").path("id").asLong();
    }
    private HttpResponse<String> lesson(Browser academic,long group,String teacher,long room,String day) throws Exception { return academic.send("POST","/api/academic/classes/"+group+"/lessons",Map.of("teacherId",user(teacher),"roomId",room,"startsAt",day+"T08:00:00","endsAt",day+"T09:00:00")); }
    private HttpResponse<String> exam(Browser academic,long group,long room,String day) throws Exception { return academic.send("PUT","/api/academic/classes/"+group+"/exam",Map.of("roomId",room,"startsAt",day+"T08:00:00","endsAt",day+"T09:00:00","version",0)); }
    private HttpResponse<String> publish(Browser academic,JsonNode exam) throws Exception { return academic.send("POST","/api/academic/exams/"+id(exam)+"/state",Map.of("status","PUBLISHED","version",exam.path("version").asInt())); }
    private long id(JsonNode value) { return value.path("id").asLong(); }
    private long user(String name) { return jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single(); }
    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }
    private JsonNode ok(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body()); }
    private Browser login(String name) throws Exception { var browser=new Browser(); browser.csrf(); ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026"))); browser.csrf(); return browser; }
    @FunctionalInterface interface Request { int send() throws Exception; }
    private int unchecked(Request request) { try { return request.send(); } catch(Exception error) { throw new RuntimeException(error); } }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String header,token;
        void csrf() throws Exception { var data=tree("/api/auth/csrf"); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        JsonNode tree(String path) throws Exception { return json.readTree(get(path).body()); }
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header(header,token).method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
