package cn.zzuit.campus;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:campus-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true"
})
class FoundationIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/admin/foundation";
    static final String PASSWORD="NewDemo@2026!";

    @Test
    void foundationRequiresItsOwnPermissionAndNeverReturnsCredentials() throws Exception {
        assertThat(new Browser().get(ROOT+"/catalog").statusCode()).isEqualTo(401);
        for(String user:List.of("20260001","teacher1001","academic1001","editor","reviewer","staff1001"))
            assertThat(login(user,"Demo@2026").get(ROOT+"/catalog").statusCode()).isEqualTo(403);
        var admin=login("admin","Demo@2026");
        var catalog=admin.get(ROOT+"/catalog");
        assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
        assertThat(catalog.body()).contains("foundation:manage","ACADEMIC_STAFF");
        var users=admin.get(ROOT+"/users");
        assertThat(users.statusCode()).isEqualTo(200);
        assertThat(users.body()).doesNotContain("\"password\":","securityVersion","password_hash","initialPassword");
        assertThat(admin.get("/api/academic/catalog").statusCode()).isEqualTo(403);
    }

    @Test
    void accountCreationHashesPasswordChecksIdentityAndAuditsWithoutSecrets() throws Exception {
        var admin=login("admin","Demo@2026"); var input=account("STUDENT");
        var created=admin.send("POST",ROOT+"/users",input);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var record=json.readTree(created.body()); long id=record.path("id").asLong();
        assertThat(record.path("roles").get(0).asText()).isEqualTo("STUDENT");
        String hash=jdbc.sql("SELECT password_hash FROM app_user WHERE id=:u").param("u",id).query(String.class).single();
        assertThat(hash).isNotEqualTo(PASSWORD); assertThat(passwords.matches(PASSWORD,hash)).isTrue();
        assertThat(login((String)input.get("username"),PASSWORD).get("/api/portal/overview").statusCode()).isEqualTo(200);
        var duplicate=new HashMap<>(input); duplicate.put("username","dup-"+UUID.randomUUID());
        assertThat(admin.send("POST",ROOT+"/users",duplicate).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username=:u").param("u",duplicate.get("username")).query(Long.class).single()).isZero();
        var audit=admin.get(ROOT+"/audit?entityType=USER&entityId="+id);
        assertThat(audit.body()).contains("USER_CREATE").doesNotContain(PASSWORD,hash,"password");
    }

    @Test
    void studentClassAndDepartmentMustAgreeAndUnknownRolesAreRejectedAtomically() throws Exception {
        var admin=login("admin","Demo@2026");
        for(String invalid:List.of("department","role","class","campus","password")) {
            var input=account("STUDENT");
            switch(invalid) {
                case "department" -> input.put("departmentId",2);
                case "role" -> input.put("roles",List.of("SUPERUSER"));
                case "class" -> input.put("classGroupId",null);
                case "campus" -> input.put("campusId",999999);
                case "password" -> input.put("initialPassword","中".repeat(30));
            }
            var response=admin.send("POST",ROOT+"/users",input);
            assertThat(response.statusCode()).as("%s %s",invalid,response.body()).isEqualTo(400);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username=:n").param("n",input.get("username")).query(Long.class).single()).isZero();
        }
    }

    @Test
    void disabledOrReassignedAccountsCannotReuseOldSessionsAfterReenable() throws Exception {
        var admin=login("admin","Demo@2026"); var input=account("CONTENT_EDITOR");
        var record=json.readTree(admin.send("POST",ROOT+"/users",input).body()); long id=record.path("id").asLong();
        var idle=login((String)input.get("username"),PASSWORD); var active=login((String)input.get("username"),PASSWORD);
        assertThat(admin.send("POST",ROOT+"/users/"+id+"/state",Map.of("enabled",false,"version",0,"reason","测试停用")).statusCode()).isEqualTo(200);
        assertThat(active.get("/api/admin/content").statusCode()).isEqualTo(401);
        assertThat(admin.send("POST",ROOT+"/users/"+id+"/state",Map.of("enabled",true,"version",1,"reason","测试恢复")).statusCode()).isEqualTo(200);
        assertThat(idle.get("/api/admin/content").statusCode()).isEqualTo(401);
        var current=login((String)input.get("username"),PASSWORD);
        var update=update(input,2); update.put("roles",List.of("SERVICE_STAFF"));
        assertThat(admin.send("PUT",ROOT+"/users/"+id,update).statusCode()).isEqualTo(200);
        assertThat(current.get("/api/me").statusCode()).isEqualTo(401);
        var relogged=login((String)input.get("username"),PASSWORD);
        assertThat(relogged.get("/api/admin/content").statusCode()).isEqualTo(403);
        assertThat(relogged.get("/api/workbench/overview").statusCode()).isEqualTo(200);
    }

    @Test
    void currentAuthoritiesAreReadEvenWhenOnlyTheRoleTableChanges() throws Exception {
        var admin=login("admin","Demo@2026"); var input=account("CONTENT_EDITOR");
        long id=json.readTree(admin.send("POST",ROOT+"/users",input).body()).path("id").asLong();
        var editor=login((String)input.get("username"),PASSWORD);
        assertThat(editor.get("/api/admin/content").statusCode()).isEqualTo(200);
        jdbc.sql("DELETE FROM user_role WHERE user_id=:u").param("u",id).update();
        assertThat(editor.get("/api/admin/content").statusCode()).isEqualTo(403);
        assertThat(editor.send("POST","/api/admin/content",Map.of("slug","live-"+UUID.randomUUID(),"category","news","title","演示文章","summary","摘要","body","正文","sourceType","DEMO","version",0)).statusCode()).isEqualTo(403);
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'CONTENT_REVIEWER')").param("u",id).update();
        assertThat(editor.get("/api/admin/content").statusCode()).isEqualTo(200);
        assertThat(editor.send("POST","/api/admin/content",Map.of("slug","deny-"+UUID.randomUUID(),"category","news","title","演示文章","summary","摘要","body","正文","sourceType","DEMO","version",0)).statusCode()).isEqualTo(403);
    }

    @Test
    void staleUpdatesDoNotChangeRolesOrAuditAndSelfLockoutIsPrevented() throws Exception {
        var admin=login("admin","Demo@2026"); var input=account("SERVICE_STAFF");
        long id=json.readTree(admin.send("POST",ROOT+"/users",input).body()).path("id").asLong();
        var change=update(input,0); change.put("fullName","已更新人员");
        assertThat(admin.send("PUT",ROOT+"/users/"+id,change).statusCode()).isEqualTo(200);
        change.put("roles",List.of("ADMIN"));
        assertThat(admin.send("PUT",ROOT+"/users/"+id,change).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT role_code FROM user_role WHERE user_id=:u").param("u",id).query(String.class).list()).containsExactly("SERVICE_STAFF");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='USER' AND entity_id=:u").param("u",id).query(Long.class).single()).isEqualTo(2);
        long self=admin.tree("/api/me").path("id").asLong();
        assertThat(admin.send("POST",ROOT+"/users/"+self+"/state",Map.of("enabled",false,"version",0,"reason","误操作测试")).statusCode()).isEqualTo(409);
    }

    @Test
    void organizationMaintenancePreservesReferencesAndRejectsStaleVersions() throws Exception {
        var admin=login("admin","Demo@2026"); String suffix=UUID.randomUUID().toString();
        long department=id(admin.send("POST",ROOT+"/departments",Map.of("name","测试学院-"+suffix,"kind","COLLEGE")));
        long major=id(admin.send("POST",ROOT+"/majors",Map.of("name","测试专业-"+suffix,"departmentId",department)));
        long group=id(admin.send("POST",ROOT+"/class-groups",Map.of("name","测试行政班-"+suffix,"majorId",major)));
        assertThat(admin.send("PUT",ROOT+"/departments/"+department,Map.of("name","更名学院-"+suffix,"kind","COLLEGE","version",0)).statusCode()).isEqualTo(200);
        assertThat(admin.send("PUT",ROOT+"/departments/"+department,Map.of("name","旧版名称","kind","COLLEGE","version",0)).statusCode()).isEqualTo(409);
        var student=account("STUDENT"); student.put("departmentId",department); student.put("classGroupId",group);
        assertThat(admin.send("POST",ROOT+"/users",student).statusCode()).isEqualTo(200);
        assertThat(admin.send("PUT",ROOT+"/majors/"+major,Map.of("name","移专业","departmentId",1,"version",0)).statusCode()).isEqualTo(409);
        assertThat(admin.send("PUT",ROOT+"/class-groups/"+group,Map.of("name","移班","majorId",1,"version",0)).statusCode()).isEqualTo(409);
        assertThat(admin.send("PUT",ROOT+"/departments/"+department,Map.of("name","更名学院-"+suffix,"kind","ADMIN","version",1)).statusCode()).isEqualTo(409);
        assertThat(admin.get(ROOT+"/catalog").body()).contains("更名学院-"+suffix,"测试行政班-"+suffix);
    }

    @Test
    void usersAndAuditSupportBoundedPaginationAndSearch() throws Exception {
        var admin=login("admin","Demo@2026"); var input=account("SERVICE_STAFF");
        long user=id(admin.send("POST",ROOT+"/users",input));
        var found=admin.tree(ROOT+"/users?q="+input.get("username")+"&role=SERVICE_STAFF&page=1&pageSize=5");
        assertThat(found.path("total").asInt()).isEqualTo(1); assertThat(found.path("items").get(0).path("id").asLong()).isEqualTo(user);
        assertThat(admin.tree(ROOT+"/users?q="+input.get("username")+"&page=2&pageSize=5").path("items").isEmpty()).isTrue();
        assertThat(admin.get(ROOT+"/users?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(admin.get(ROOT+"/audit?page=0").statusCode()).isEqualTo(400);
    }

    @Test
    void concurrentAdminsCannotBothDisableEachOther() throws Exception {
        var admin=login("admin","Demo@2026"); var first=account("ADMIN"); var second=account("ADMIN");
        long firstId=id(admin.send("POST",ROOT+"/users",first)); long secondId=id(admin.send("POST",ROOT+"/users",second));
        var firstSession=login((String)first.get("username"),PASSWORD); var secondSession=login((String)second.get("username"),PASSWORD);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(()-> { start.await(); return firstSession.send("POST",ROOT+"/users/"+secondId+"/state",Map.of("enabled",false,"version",0,"reason","并发停用测试")); });
            var two=executor.submit(()-> { start.await(); return secondSession.send("POST",ROOT+"/users/"+firstId+"/state",Map.of("enabled",false,"version",0,"reason","并发停用测试")); });
            start.countDown();
            assertThat(List.of(one.get().statusCode(),two.get().statusCode())).containsExactlyInAnyOrder(200,401);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id IN (:one,:two) AND enabled=TRUE").param("one",firstId).param("two",secondId).query(Long.class).single()).isEqualTo(1);
        // Restore the shared test context to its original administrator set.
        long remaining=jdbc.sql("SELECT id FROM app_user WHERE id IN (:one,:two) AND enabled=TRUE").param("one",firstId).param("two",secondId).query(Long.class).single();
        assertThat(admin.send("POST",ROOT+"/users/"+remaining+"/state",Map.of("enabled",false,"version",0,"reason","结束并发测试")).statusCode()).isEqualTo(200);
    }

    @Test
    void profileEditsRefreshTheSessionAndDepartmentTransfersRequireRelogin() throws Exception {
        var admin=login("admin","Demo@2026"); var input=account("ACADEMIC_STAFF");
        long id=id(admin.send("POST",ROOT+"/users",input)); var academic=login((String)input.get("username"),PASSWORD);
        assertThat(academic.tree("/api/academic/catalog").path("classes").isEmpty()).isFalse();
        var change=update(input,0); change.put("fullName","新姓名演示人员");
        assertThat(admin.send("PUT",ROOT+"/users/"+id,change).statusCode()).isEqualTo(200);
        assertThat(academic.tree("/api/me").path("fullName").asText()).isEqualTo("新姓名演示人员");
        change.put("version",1); change.put("departmentId",2);
        assertThat(admin.send("PUT",ROOT+"/users/"+id,change).statusCode()).isEqualTo(200);
        assertThat(academic.get("/api/academic/catalog").statusCode()).isEqualTo(401);
        var renewed=login((String)input.get("username"),PASSWORD);
        long scopedCount=jdbc.sql("SELECT COUNT(*) FROM teaching_class WHERE department_id=2").query(Long.class).single();
        assertThat(renewed.tree("/api/academic/catalog").path("classes").size()).isEqualTo((int)scopedCount);
        assertThat(renewed.tree("/api/me").path("departmentName").asText()).contains("机");
    }

    @Test
    void classCreationAndDepartmentTransferCannotLeaveCrossDepartmentTeachingRelations() throws Exception {
        var admin=login("admin","Demo@2026"); var academic=login("academic1001","Demo@2026"); var input=account("TEACHER");
        long teacher=id(admin.send("POST",ROOT+"/users",input));
        long term=academic.tree("/api/academic/catalog").path("terms").get(0).path("id").asLong();
        long course=academic.tree("/api/academic/catalog").path("courses").get(0).path("id").asLong();
        long student=academic.tree("/api/academic/catalog").path("students").get(0).path("id").asLong();
        var transfer=update(input,0); transfer.put("departmentId",2);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(()-> { start.await(); return admin.send("PUT",ROOT+"/users/"+teacher,transfer); });
            var two=executor.submit(()-> { start.await(); return academic.send("POST","/api/academic/classes",Map.of("termId",term,"courseId",course,"name","学院归属并发测试","teacherIds",List.of(teacher),"studentIds",List.of(student))); });
            start.countDown(); var move=one.get(); var create=two.get();
            assertThat((move.statusCode()==200 && create.statusCode()==400) || (move.statusCode()==409 && create.statusCode()==200))
                .as("transfer=%s, creation=%s",move.body(),create.body()).isTrue();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM teaching_instructor ti JOIN teaching_class tc ON tc.id=ti.class_id JOIN app_user u ON u.id=ti.teacher_id WHERE ti.teacher_id=:u AND ti.active=TRUE AND u.department_id<>tc.department_id")
            .param("u",teacher).query(Long.class).single()).isZero();
    }

    private long id(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body()).path("id").asLong(); }
    private Map<String,Object> account(String role) {
        var input=new HashMap<String,Object>(); input.put("username","test-"+UUID.randomUUID()); input.put("fullName","基础管理演示人员");
        input.put("identityNumber","ID-"+UUID.randomUUID()); input.put("campusId",1); input.put("departmentId",1);
        input.put("classGroupId",role.equals("STUDENT")?1:null); input.put("roles",List.of(role)); input.put("initialPassword",PASSWORD); return input;
    }
    private Map<String,Object> update(Map<String,Object> input,int version) { var result=new HashMap<>(input); result.remove("initialPassword"); result.remove("username"); result.put("version",version); result.put("reason","回归测试变更"); return result; }
    private Browser login(String name,String password) throws Exception {
        // These fixtures represent established accounts; first-login gating is
        // covered through the real password endpoint in CredentialLifecycleIntegrationTest.
        if(name.startsWith("test-")) jdbc.sql("UPDATE app_user SET must_change_password=FALSE WHERE username=:name").param("name",name).update();
        var browser=new Browser(); browser.csrf(); assertThat(browser.send("POST","/api/auth/login",Map.of("username",name,"password",password)).statusCode()).isEqualTo(200); browser.csrf(); return browser; }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String header,token;
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        JsonNode tree(String path) throws Exception { return json.readTree(get(path).body()); }
        void csrf() throws Exception { var data=tree("/api/auth/csrf"); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> send(String method,String path,Map<String,?> input) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header(header,token)
                .method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
