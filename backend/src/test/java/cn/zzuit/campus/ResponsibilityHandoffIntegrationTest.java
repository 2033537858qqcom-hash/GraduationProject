package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zzuit.campus.bootstrap.ReservationDemoInitializer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:handoff;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class ResponsibilityHandoffIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 final JsonMapper json=JsonMapper.builder().build();
 @Test void normalDisableRequiresMovingVenueResponsibility()throws Exception{
  long u=staff("STUDENT_AFFAIRS");venue(u);
  assertThat(state(u,false,false).statusCode()).isEqualTo(409);
 }
 @Test void losingRoleWithConfiguredVenueRequiresHandoff()throws Exception{
  long u=staff("STUDENT_AFFAIRS");venue(u);
  var result=login("admin").send("PUT",root(u),Map.of("fullName","交接人员","identityNumber","handoff-"+u,"campusId",1,"departmentId",1,"roles",java.util.List.of("TEACHER"),"version",0,"reason","调岗"));
  assertThat(result.statusCode()).as(result.body()).isEqualTo(409);
 }
 @Test void previewReturnsCountsAndAuthorizedConfigurationEntryWithoutPrivateTasks()throws Exception{
  long u=staff("STUDENT_AFFAIRS");venue(u);
  var data=ok(login("admin").get(root(u)+"/responsibilities"));
  assertThat(data.path("blockingCount").asLong()).isEqualTo(1);
  assertThat(data.toString()).contains("VENUE","reservation:manage").doesNotContain("contactPhone","activityTheme");
 }
 @Test void emergencyDisableRevokesSessionAndExposesOrphanResourceUntilSuccessorAssigned()throws Exception{
  long u=staff("STUDENT_AFFAIRS");long v=venue(u);String name=jdbc.sql("SELECT username FROM app_user WHERE id=:u").param("u",u).query(String.class).single();var old=login(name);
  long before=exception("VENUE");ok(state(u,false,true));assertThat(old.get("/api/me").statusCode()).isEqualTo(401);
  assertThat(exception("VENUE")).isEqualTo(before+1);
  long successor=jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single();
  ok(login("affairs1001").send("POST","/api/reservations/venues/"+v+"/managers",Map.of("userId",successor)));
  assertThat(exception("VENUE")).isEqualTo(before);
  assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE action='USER_EMERGENCY_DISABLE' AND entity_id=:u").param("u",u).query(Long.class).single()).isEqualTo(1);
 }
 @Test void repairScopeRequiresHandoffButEmergencyCanRevokeImmediately()throws Exception{
  long u=staff("REPAIR_TECHNICIAN");jdbc.sql("INSERT INTO repair_staff_scope(user_id,capability,campus_id,category) VALUES(:u,'WORK',1,'WATER')").param("u",u).update();
  assertThat(state(u,false,false).statusCode()).isEqualTo(409);ok(state(u,false,true));
 }
 @Test void dormitoryScopeRequiresHandoff()throws Exception{
  long u=staff("DORM_MANAGER");long b=jdbc.sql("SELECT MIN(id) FROM dorm_building").query(Long.class).single();
  jdbc.sql("INSERT INTO dorm_manager_scope(user_id,building_id) VALUES(:u,:b)").param("u",u).param("b",b).update();
  assertThat(state(u,false,false).statusCode()).isEqualTo(409);ok(state(u,false,true));
 }
 @Test void previewAndExceptionOverviewRequireFoundationPermission()throws Exception{
  var student=login("20260001");assertThat(student.get(root(1)+"/responsibilities").statusCode()).isEqualTo(403);
  assertThat(student.get("/api/admin/foundation/responsibility-exceptions").statusCode()).isEqualTo(403);
 }
 @Test void emergencyCannotBypassCurrentAdministratorProtection()throws Exception{
  long u=jdbc.sql("SELECT id FROM app_user WHERE username='admin'").query(Long.class).single();assertThat(state(u,false,true).statusCode()).isEqualTo(409);
 }
 @Test void removingScopeAllowsNormalDisableAndUnrelatedProfileEditRemainsAllowed()throws Exception{
  long u=staff("STUDENT_AFFAIRS");long v=venue(u);
  ok(login("admin").send("PUT",root(u),Map.of("fullName","交接更名","identityNumber","handoff-"+u,"campusId",1,"departmentId",1,"roles",java.util.List.of("STUDENT_AFFAIRS"),"version",0,"reason","更名")));
  jdbc.sql("DELETE FROM venue_manager WHERE venue_id=:v").param("v",v).update();ok(state(u,false,false));
 }
 @Test void staleEmergencyRequestIsRejected()throws Exception{
  long u=staff("STUDENT_AFFAIRS");jdbc.sql("UPDATE app_user SET version=1 WHERE id=:u").param("u",u).update();
  assertThat(login("admin").send("POST",root(u)+"/state",Map.of("enabled",false,"version",0,"reason","紧急撤权","emergency",true)).statusCode()).isEqualTo(409);
 }
 private String root(long u){return "/api/admin/foundation/users/"+u;}
 private HttpResponse<String> state(long u,boolean enabled,boolean emergency)throws Exception{return login("admin").send("POST",root(u)+"/state",Map.of("enabled",enabled,"emergency",emergency,"version",jdbc.sql("SELECT version FROM app_user WHERE id=:u").param("u",u).query(Integer.class).single(),"reason","岗位交接测试"));}
 private long exception(String code)throws Exception{var items=ok(login("admin").get("/api/admin/foundation/responsibility-exceptions"));for(var item:items)if(item.path("code").asText().equals(code))return item.path("count").asLong();throw new AssertionError(code);}
 private long staff(String role){String n="handoff_"+UUID.randomUUID().toString().substring(0,8);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id) SELECT :n,password_hash,'交接人员',:n,1,1 FROM app_user WHERE username='teacher1001'").param("n",n).update();long u=jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",n).query(Long.class).single();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,:r)").param("u",u).param("r",role).update();return u;}
 private long venue(long u)throws Exception{var input=new java.util.HashMap<String,Object>();input.put("campusId",1);input.put("code","HAND-"+UUID.randomUUID());input.put("name","交接资源");input.put("category","VENUE");input.put("buildingName","测试楼");input.put("locationDetail","测试房间");input.put("capacity",20);input.put("enabled",true);input.put("managerUserIds",java.util.List.of(u));return ok(login("affairs1001").send("POST","/api/reservations/venues",input)).path("id").asLong();}
    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private Browser login(String name) throws Exception { return login(name,"Demo@2026"); }
    private Browser login(String name,String password) throws Exception {
        var browser = new Browser();
        browser.csrf();
        ok(browser.send("POST", "/api/auth/login", Map.of("username", name, "password", password)));
        browser.csrf();
        return browser;
    }

    private record Staff(long id, Browser browser) {}

    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String header, token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception {
            var data = json.readTree(get("/api/auth/csrf").body());
            header = data.path("headerName").asText(); token = data.path("token").asText();
        }
        HttpResponse<String> send(String method, String path, Map<String, ?> body) throws Exception {
            return http.send(builder(path).header(header, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
