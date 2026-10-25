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
 "spring.datasource.url=jdbc:h2:mem:credential-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class CredentialLifecycleIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();
 static final String OLD="InitialPass2026!", NEW="UpdatedPass2026!", TEMP="TemporaryPass2026!";
 @Test void selfChangeRevokesAllSessionsAndNeverLeaksCredentials() throws Exception {
  var f=fixture("STUDENT");var a=login(f.name(),OLD);var idle=login(f.name(),OLD);
  var response=a.send("POST","/api/auth/password",Map.of("currentPassword",OLD,"newPassword",NEW));
  assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
  assertThat(a.get("/api/me").statusCode()).isEqualTo(401);assertThat(idle.get("/api/me").statusCode()).isEqualTo(401);
  assertThat(loginRequest(f.name(),OLD).statusCode()).isEqualTo(401);
  var renewed=login(f.name(),NEW);assertThat(ok(renewed.get("/api/me")).path("passwordChangeRequired").asBoolean()).isFalse();
  var audit=login("admin").get("/api/admin/foundation/audit?entityType=USER&entityId="+f.id());
  assertThat(audit.body()).contains("USER_PASSWORD_CHANGE").doesNotContain(OLD,NEW,hash(f));
  assertThat(encoder.matches(NEW,hash(f))).isTrue();
 }
 @Test void wrongCurrentPasswordDoesNotChangeHashOrSession() throws Exception {
  var f=fixture("STUDENT");var b=login(f.name(),OLD);String before=hash(f);
  assertThat(b.send("POST","/api/auth/password",Map.of("currentPassword","WrongPass2026!","newPassword",NEW)).statusCode()).isEqualTo(400);
  assertThat(hash(f)).isEqualTo(before);assertThat(b.get("/api/me").statusCode()).isEqualTo(200);
 }
 @ParameterizedTest @ValueSource(strings={"SHORT","LETTERS","BYTES","SAME"})
 void passwordPolicyRejectsWeakLongOrReusedCredentials(String kind)throws Exception{
  var f=fixture("STUDENT");var b=login(f.name(),OLD);String pass=switch(kind){case "SHORT"->"Abc1";case "LETTERS"->"abcdefghijk";case "BYTES"->"中".repeat(25)+"a1";default->OLD;};
  assertThat(b.send("POST","/api/auth/password",Map.of("currentPassword",OLD,"newPassword",pass)).statusCode()).isEqualTo(400);
  assertThat(encoder.matches(OLD,hash(f))).isTrue();
 }
 @Test void administratorResetRequiresFirstChangeAndInvalidatesExistingSessions()throws Exception{
  var f=fixture("STUDENT");var old=login(f.name(),OLD);var admin=login("admin");
  var reset=ok(admin.send("POST","/api/admin/foundation/users/"+f.id()+"/password",Map.of("version",0,"newPassword",TEMP,"reason","本人线下核验后重置")));
  assertThat(reset.path("passwordChangeRequired").asBoolean()).isTrue();assertThat(reset.toString()).doesNotContain(TEMP,hash(f));
  assertThat(old.get("/api/me").statusCode()).isEqualTo(401);assertThat(loginRequest(f.name(),OLD).statusCode()).isEqualTo(401);
  var initial=login(f.name(),TEMP);assertThat(ok(initial.get("/api/me")).path("passwordChangeRequired").asBoolean()).isTrue();
  assertThat(initial.get("/api/portal/overview").statusCode()).isEqualTo(403);
  assertThat(initial.send("POST","/api/reservations/submit",Map.of()).body()).contains("PASSWORD_CHANGE_REQUIRED");
  assertThat(initial.send("POST","/api/auth/password",Map.of("currentPassword",TEMP,"newPassword",NEW)).statusCode()).isEqualTo(204);
  assertThat(login(f.name(),NEW).get("/api/portal/overview").statusCode()).isEqualTo(200);
  assertThat(admin.get("/api/admin/foundation/audit?entityType=USER&entityId="+f.id()).body()).contains("USER_PASSWORD_RESET").doesNotContain(TEMP,NEW,hash(f));
 }
 @Test void resetRequiresManagementPermissionCurrentVersionAndReason()throws Exception{
  var f=fixture("STUDENT");String before=hash(f);var student=login(f.name(),OLD);var admin=login("admin");
  String path="/api/admin/foundation/users/"+f.id()+"/password";
  assertThat(student.send("POST",path,Map.of("version",0,"newPassword",TEMP,"reason","无权重置")).statusCode()).isEqualTo(403);
  assertThat(admin.send("POST",path,Map.of("version",99,"newPassword",TEMP,"reason","旧版本")).statusCode()).isEqualTo(409);
  assertThat(admin.send("POST",path,Map.of("version",0,"newPassword",TEMP,"reason"," ")).statusCode()).isEqualTo(400);
  assertThat(hash(f)).isEqualTo(before);
 }
 @Test void administratorCannotResetOwnPasswordOrDisabledAccount()throws Exception{
  var admin=login("admin");long own=ok(admin.get("/api/me")).path("id").asLong();
  assertThat(admin.send("POST","/api/admin/foundation/users/"+own+"/password",Map.of("version",0,"newPassword",TEMP,"reason","自身重置")).statusCode()).isEqualTo(400);
  var f=fixture("STUDENT");jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE id=:id").param("id",f.id()).update();
  assertThat(admin.send("POST","/api/admin/foundation/users/"+f.id()+"/password",Map.of("version",0,"newPassword",TEMP,"reason","停用账户")).statusCode()).isEqualTo(409);
 }
 @Test void newlyCreatedAccountsMustChangeInitialPasswordBeforeBusinessAccess()throws Exception{
  var admin=login("admin");String name="first_"+UUID.randomUUID().toString().substring(0,8);
  var created=ok(admin.send("POST","/api/admin/foundation/users",Map.of("username",name,"fullName","新建改密验收","identityNumber",name,"campusId",1,"departmentId",1,"roles",java.util.List.of("TEACHER"),"initialPassword",OLD)));
  assertThat(created.path("passwordChangeRequired").asBoolean()).isTrue();var b=login(name,OLD);
  assertThat(b.get("/api/workbench/overview").body()).contains("PASSWORD_CHANGE_REQUIRED");
  assertThat(b.get("/api/public/school").statusCode()).isEqualTo(200);
  assertThat(b.send("POST","/api/auth/password",Map.of("currentPassword",OLD,"newPassword",NEW)).statusCode()).isEqualTo(204);
  assertThat(login(name,NEW).get("/api/workbench/overview").statusCode()).isEqualTo(200);
 }
 @Test void passwordAttemptsAreRateLimitedWithoutInvalidatingAccount()throws Exception{
  var f=fixture("STUDENT");var b=login(f.name(),OLD);
  for(int i=0;i<8;i++)assertThat(b.send("POST","/api/auth/password",Map.of("currentPassword","WrongPass2026!","newPassword",NEW)).statusCode()).isEqualTo(400);
  assertThat(b.send("POST","/api/auth/password",Map.of("currentPassword",OLD,"newPassword",NEW)).statusCode()).isEqualTo(429);
  assertThat(encoder.matches(OLD,hash(f))).isTrue();
 }
 private record Fixture(long id,String name){}
 private Fixture fixture(String role){String name="cred_"+UUID.randomUUID().toString().substring(0,8);
  jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id) VALUES(:name,:hash,'凭据测试',:name,1,1,1)").param("name",name).param("hash",encoder.encode(OLD)).update();
  long id=jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",id).param("role",role).update();return new Fixture(id,name);}
 private String hash(Fixture f){return jdbc.sql("SELECT password_hash FROM app_user WHERE id=:id").param("id",f.id()).query(String.class).single();}
 private HttpResponse<String> loginRequest(String name,String pass)throws Exception{var b=new Browser();b.csrf();return b.send("POST","/api/auth/login",Map.of("username",name,"password",pass));}
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
