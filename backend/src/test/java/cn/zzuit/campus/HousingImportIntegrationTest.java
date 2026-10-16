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
    "spring.datasource.url=jdbc:h2:mem:campus-housing-import;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class HousingImportIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/housing/imports",ADMIN="/api/admin/housing",HEADER="学号,房间编号,床位编号,入住说明\n";
    @Test void requiresCurrentRoleScopeOwnerAndCsrf() throws Exception {
        var home=home();var input=input(home,HEADER+student()+",R101,A,报到");
        assertThat(new Browser().get(ROOT+"?buildingId="+home.building()).statusCode()).isEqualTo(401);
        for(String name:List.of("admin","teacher1001","20260001","dispatcher1001"))assertThat(login(name).send("POST",ROOT+"/preview",input).statusCode()).isEqualTo(403);
        var manager=login(home.manager());
        assertThat(manager.http.send(manager.builder(ROOT+"/preview").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var batch=ok(manager.send("POST",ROOT+"/preview",input));
        var other=home();var outsider=login(other.manager());
        assertThat(outsider.get(ROOT+"/"+batch.path("token").asText()).statusCode()).isEqualTo(404);
        assertThat(outsider.send("POST",confirmPath(batch),Map.of()).statusCode()).isEqualTo(404);
        assertThat(outsider.get(ROOT+"/template?buildingId="+home.building()).statusCode()).isEqualTo(404);
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id AND role_code='DORM_MANAGER'").param("id",user(home.manager())).update();
        assertThat(manager.send("POST",confirmPath(batch),Map.of()).statusCode()).isEqualTo(403);
        assertThat(stays(home)).isZero();
    }
    @Test void invalidRowsHaveLineErrorsAndNeverStageOrRegisterStays() throws Exception {
        var home=home();var manager=login(home.manager());var name=student();
        String csv=HEADER+name+",R101,A,报到\n"+name+",R101,B,重复\n"+student()+",R101,A,重复床位\nteacher1001,R101,C,教师\nunknown,R999,A,无配置\n,R101,D,\n";
        long previews=count("housing_import_batch"),notices=count("user_notice");
        var batch=ok(manager.send("POST",ROOT+"/preview",input(home,csv)));
        assertThat(batch.path("status").asText()).isEqualTo("INVALID");assertThat(batch.path("token").isNull()).isTrue();assertThat(batch.path("invalid").asInt()).isEqualTo(5);assertThat(batch.path("ignored").asInt()).isEqualTo(1);
        assertThat(batch.path("rows").get(0).path("errors").toString()).contains("同一学生","同一床位");
        assertThat(batch.path("rows").get(4).path("line").asInt()).isEqualTo(6);
        assertThat(stays(home)).isZero();assertThat(count("housing_import_batch")).isEqualTo(previews);assertThat(count("user_notice")).isEqualTo(notices);
        assertThat(batch.toString()).doesNotContain("password_hash","identity_number");
    }
    @Test void previewAndWholeBatchConfirmationHavePersistentIdempotentReceipts() throws Exception {
        var home=home();var manager=login(home.manager());var one=student();var two=student();
        var batch=preview(manager,home,one,two);assertThat(stays(home)).isZero();assertThat(batch.path("canConfirm").asBoolean()).isTrue();
        var done=ok(manager.send("POST",confirmPath(batch),Map.of("rows",List.of(Map.of("studentId",user("teacher1001"))))));
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");assertThat(done.path("rows").get(0).path("assignmentId").asLong()).isPositive();
        assertThat(stays(home)).isEqualTo(2);assertThat(bedStatus(home,"A")).isEqualTo("OCCUPIED");assertThat(bedStatus(home,"B")).isEqualTo("OCCUPIED");
        assertThat(ok(manager.send("POST",confirmPath(batch),Map.of())).toString()).isEqualTo(done.toString());
        assertThat(ok(login(home.manager()).get(ROOT+"/"+batch.path("token").asText())).path("id").asLong()).isEqualTo(batch.path("id").asLong());
        assertThat(ok(manager.get(ROOT+"?buildingId="+home.building())).size()).isEqualTo(1);
        assertThat(ok(manager.get("/api/housing/history?buildingId="+home.building())).path("items").get(0).path("importBatchId").asLong()).isEqualTo(batch.path("id").asLong());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE user_id IN (:one,:two) AND title='住宿入住登记'").param("one",user(one)).param("two",user(two)).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='HOUSING_IMPORT_BATCH' AND entity_id=:id").param("id",batch.path("id").asLong()).query(Long.class).single()).isEqualTo(1);
        assertThat(ok(login(one).get("/api/housing/mine")).path("current").path("buildingName").asText()).isEqualTo("批量测试楼");
    }
    @Test void expiryAndEveryChangedResourceInvalidateTheWholePreview() throws Exception {
        for(String change:List.of("expiry","room","student","scope","occupied","disabled","security")) {
            var home=home();var manager=login(home.manager());var one=student();var two=student();var batch=preview(manager,home,one,two);
            switch(change) {
                case "expiry"->jdbc.sql("UPDATE housing_import_batch SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=:id").param("id",batch.path("id").asLong()).update();
                case "room"->jdbc.sql("UPDATE dorm_room SET name='已变更',version=version+1 WHERE id=:id").param("id",home.room()).update();
                case "student"->jdbc.sql("UPDATE app_user SET full_name='已变更',version=version+1 WHERE username=:name").param("name",two).update();
                case "scope"->jdbc.sql("UPDATE dorm_manager_scope SET version=version+1 WHERE building_id=:id").param("id",home.building()).update();
                case "occupied"->jdbc.sql("UPDATE dorm_bed SET status='RESERVED',version=version+1 WHERE room_id=:id AND code='B'").param("id",home.room()).update();
                case "disabled"->jdbc.sql("UPDATE dorm_bed SET enabled=FALSE,version=version+1 WHERE room_id=:id AND code='B'").param("id",home.room()).update();
                case "security"->{jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE username=:name").param("name",home.manager()).update();manager=login(home.manager());}
                default->throw new AssertionError();
            }
            assertThat(manager.send("POST",confirmPath(batch),Map.of()).statusCode()).as(change).isEqualTo(409);assertThat(stays(home)).as(change).isZero();assertThat(bedStatus(home,"A")).isEqualTo("VACANT");
        }
    }
    @Test void revalidatesLiveStudentAndBedEligibilityAndScopeOnEveryRead() throws Exception {
        var home=home();var manager=login(home.manager());var one=student();var two=student();
        ok(manager.send("POST","/api/housing/assignments",Map.of("requestKey",key(),"studentId",user(one),"bedId",bed(home,"A"),"reason","先登记")));
        jdbc.sql("UPDATE dorm_bed SET status='MAINTENANCE',version=version+1 WHERE id=:id").param("id",bed(home,"B")).update();
        var invalid=ok(manager.send("POST",ROOT+"/preview",input(home,HEADER+one+",R101,A,已有\n"+two+",R101,B,维护")));
        assertThat(invalid.path("invalid").asInt()).isEqualTo(2);assertThat(invalid.toString()).contains("已有在住","不可入住");
        var good=ok(manager.send("POST",ROOT+"/preview",input(home,HEADER+two+",R101,C,报到")));
        jdbc.sql("UPDATE dorm_manager_scope SET enabled=FALSE,version=version+1 WHERE building_id=:id").param("id",home.building()).update();
        for(String path:List.of(ROOT+"/"+good.path("token").asText(),ROOT+"?buildingId="+home.building(),ROOT+"/template?buildingId="+home.building()))assertThat(manager.get(path).statusCode()).isEqualTo(404);
        assertThat(manager.send("POST",confirmPath(good),Map.of()).statusCode()).isEqualTo(404);
    }
    @Test void competingBatchesCommitOneCompleteBatchAndNoPartialLoser() throws Exception {
        var home=home();var manager=login(home.manager());
        var first=preview(manager,home,student(),student());var second=preview(manager,home,student(),student());
        var one=CompletableFuture.supplyAsync(()->sendUnchecked(manager,confirmPath(first)));
        var two=CompletableFuture.supplyAsync(()->sendUnchecked(manager,confirmPath(second)));
        assertThat(List.of(one.get().statusCode(),two.get().statusCode())).containsExactlyInAnyOrder(200,409);assertThat(stays(home)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM housing_import_batch WHERE building_id=:id AND status='COMPLETED'").param("id",home.building()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void laterNoticeFailureRollsBackEarlierStaysNoticesAuditAndReceipt() throws Exception {
        var home=home();var manager=login(home.manager());var one=student();var two=student();var batch=preview(manager,home,one,two);
        String constraint="test_import_"+UUID.randomUUID().toString().replace("-","");
        jdbc.sql("ALTER TABLE user_notice ADD CONSTRAINT "+constraint+" CHECK(user_id<>"+user(two)+")").update();
        try {
            assertThat(manager.send("POST",confirmPath(batch),Map.of()).statusCode()).isEqualTo(409);
            assertThat(stays(home)).isZero();assertThat(bedStatus(home,"A")).isEqualTo("VACANT");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE user_id IN (:one,:two)").param("one",user(one)).param("two",user(two)).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='HOUSING_STAY' AND summary LIKE :s").param("s",one+"%").query(Long.class).single()).isZero();
            assertThat(ok(manager.get(ROOT+"/"+batch.path("token").asText())).path("status").asText()).isEqualTo("PREVIEW");
        } finally {jdbc.sql("ALTER TABLE user_notice DROP CONSTRAINT "+constraint).update();}
        assertThat(ok(manager.send("POST",confirmPath(batch),Map.of())).path("status").asText()).isEqualTo("COMPLETED");assertThat(stays(home)).isEqualTo(2);
    }
    @Test void templateUsesOnlyConfiguredVacantResourcesAndSafeSpreadsheetCodes() throws Exception {
        var home=home();var manager=login(home.manager());
        jdbc.sql("UPDATE dorm_bed SET code='-C' WHERE room_id=:id AND code='C'").param("id",home.room()).update();
        jdbc.sql("UPDATE dorm_bed SET status='MAINTENANCE' WHERE room_id=:id AND code='B'").param("id",home.room()).update();
        var template=manager.get(ROOT+"/template?buildingId="+home.building());assertThat(template.statusCode()).isEqualTo(200);
        assertThat(template.body()).startsWith("\uFEFF学号,房间编号,床位编号,入住说明").contains("\"'-C\"").doesNotContain("\"B\"","20260001","identity_number");
        assertThat(template.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var input=template.body().replace("\"\",\"R101\",\"A\",\"\"","\""+student()+"\",\"R101\",\"A\",\"报到\"");
        assertThat(ok(manager.send("POST",ROOT+"/preview",input(home,input))).path("total").asInt()).isEqualTo(1);
    }
    private Home home()throws Exception {
        var admin=login("admin");String manager=account("im","导入测试宿管","dorm1001","DORM_MANAGER");
        long building=ok(admin.send("POST",ADMIN+"/buildings",Map.of("campusId",1,"code",key(),"name","批量测试楼","provenance","DEMO","version",0,"reason","独立测试"))).path("id").asLong();
        long room=ok(admin.send("POST",ADMIN+"/rooms",Map.of("buildingId",building,"code","R101","name","101 室","floorLabel","1 层","version",0,"reason","独立测试"))).path("id").asLong();
        for(String code:List.of("A","B","C"))ok(admin.send("POST",ADMIN+"/beds",Map.of("roomId",room,"code",code,"label",code+" 号床","version",0,"reason","独立测试")));
        ok(admin.send("POST",ADMIN+"/scopes",Map.of("userId",user(manager),"buildingId",building,"version",0,"reason","独立测试")));
        return new Home(building,room,manager);
    }
    private JsonNode preview(Browser manager,Home home,String one,String two)throws Exception {return ok(manager.send("POST",ROOT+"/preview",input(home,HEADER+one+",R101,A,报到\n"+two+",R101,B,报到")));}
    private Map<String,Object> input(Home home,String csv) {return Map.of("buildingId",home.building(),"csv",csv,"sourceName","初始入住.csv");}
    private String confirmPath(JsonNode batch) {return ROOT+"/"+batch.path("token").asText()+"/confirm";}
    private String student(){return account("is","导入测试学生","20260001","STUDENT");}
    private String key(){return "HI"+UUID.randomUUID().toString().replace("-","");}
    private String account(String prefix,String label,String source,String role){String name=prefix+UUID.randomUUID().toString().replace("-","").substring(0,12);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :u,password_hash,:n,:num,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username=:source").param("u",name).param("n",label).param("num",prefix+"-"+name).param("source",source).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",user(name)).param("role",role).update();return name;}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private long count(String table){return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single();}
    private long stays(Home home){return jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE bed_id IN (SELECT id FROM dorm_bed WHERE room_id=:r)").param("r",home.room()).query(Long.class).single();}
    private long bed(Home home,String code){return jdbc.sql("SELECT id FROM dorm_bed WHERE room_id=:id AND code=:code").param("id",home.room()).param("code",code).query(Long.class).single();}
    private String bedStatus(Home home,String code){return jdbc.sql("SELECT status FROM dorm_bed WHERE id=:id").param("id",bed(home,code)).query(String.class).single();}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private HttpResponse<String> sendUnchecked(Browser actor,String path){try{return actor.send("POST",path,Map.of());}catch(Exception ex){throw new RuntimeException(ex);}}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private record Home(long building,long room,String manager) {}
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
