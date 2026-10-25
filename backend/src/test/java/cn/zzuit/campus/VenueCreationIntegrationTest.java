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
 "spring.datasource.url=jdbc:h2:mem:venue-creation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class VenueCreationIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();
 static final String OLD="InitialPass2026!", NEW="UpdatedPass2026!", TEMP="TemporaryPass2026!";
 @Test void westResourceCanBeCreatedLocatedAssignedAndBookedWithoutSeedChanges()throws Exception{
  var manager=login("affairs1001");var input=form();var created=ok(manager.send("POST","/api/reservations/venues",input));long id=created.path("id").asLong();
  assertThat(created.path("campusId").asLong()).isEqualTo(2);assertThat(created.path("campusName").asText()).isEqualTo("西校区");assertThat(created.path("placeId").asText()).isEqualTo("west-library");
  var book=new java.util.HashMap<String,Object>();var start=java.time.LocalDateTime.now().plusDays(45);
  book.put("requestKey",UUID.randomUUID().toString());book.put("venueId",id);book.put("activityTheme","西校区场地新增验收");book.put("activityType","ACADEMIC");book.put("expectedParticipants",12);book.put("contactPhone","13800138000");book.put("startsAt",start.toString());book.put("endsAt",start.plusHours(1).toString());
  var booking=ok(login("20260001").send("POST","/api/reservations/submit",book));
  assertThat(ok(manager.get("/api/reservations/"+booking.path("id").asLong())).path("status").asText()).isEqualTo("SUBMITTED");
  String audit=login("admin").get("/api/admin/foundation/audit?entityType=VENUE&entityId="+id).body();
  assertThat(audit).contains("VENUE_CREATE",(String)input.get("code"));
 }
 @Test void catalogUsesTheCurrentTwoCampusMapIdentities()throws Exception{
  var data=ok(login("affairs1001").get("/api/reservations/venues/catalog"));
  assertThat(data.path("campuses").size()).isEqualTo(2);assertThat(data.path("places").toString()).contains("west-library","east-library");
 }
 @Test void disabledDraftResourceCanBeConfiguredBeforeOpening()throws Exception{
  var input=form();input.put("enabled",false);input.put("managerUserIds",java.util.List.of());input.put("placeId","");
  var created=ok(login("affairs1001").send("POST","/api/reservations/venues",input));
  assertThat(created.path("enabled").asBoolean()).isFalse();assertThat(created.path("placeId").isNull()).isTrue();
  var listed=ok(login("20260001").get("/api/reservations/venues?keyword="+input.get("code")));assertThat(listed.size()).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"CAMPUS","PLACE","CROSS_CAMPUS","CAPACITY","NAME","CATEGORY","NO_OWNER","DUPLICATE_OWNER","INVALID_OWNER"})
 void invalidResourceNeverLeavesAPartialRow(String kind)throws Exception{
  var input=form();switch(kind){case "CAMPUS"->input.put("campusId",9999);case "PLACE"->input.put("placeId","imaginary-building");case "CROSS_CAMPUS"->input.put("placeId","east-library");case "CAPACITY"->input.put("capacity",0);case "NAME"->input.put("name"," ");case "CATEGORY"->input.put("category","OTHER");case "NO_OWNER"->input.put("managerUserIds",java.util.List.of());case "DUPLICATE_OWNER"->input.put("managerUserIds",java.util.List.of(reviewer(),reviewer()));case "INVALID_OWNER"->input.put("managerUserIds",java.util.List.of(999999));}
  var response=login("affairs1001").send("POST","/api/reservations/venues",input);
  assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
  assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_resource WHERE code=:c").param("c",input.get("code")).query(Long.class).single()).isZero();
 }
 @Test void onlyResourceManagersCanCreateAndCodesAreUniqueWithinCampus()throws Exception{
  var input=form();assertThat(login("20260001").send("POST","/api/reservations/venues",input).statusCode()).isEqualTo(403);
  assertThat(login("admin").send("POST","/api/reservations/venues",input).statusCode()).isEqualTo(403);
  var manager=login("affairs1001");ok(manager.send("POST","/api/reservations/venues",input));
  assertThat(manager.send("POST","/api/reservations/venues",input).statusCode()).isEqualTo(409);
  input.put("campusId",1);input.put("placeId","east-library");assertThat(ok(manager.send("POST","/api/reservations/venues",input)).path("campusId").asLong()).isEqualTo(1);
 }
 private long reviewer(){return jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single();}
 private java.util.HashMap<String,Object> form(){var input=new java.util.HashMap<String,Object>();input.put("campusId",2);input.put("code","WEST-"+UUID.randomUUID());input.put("name","西校区图书馆研讨室（演示）");input.put("category","VENUE");input.put("placeId","west-library");input.put("buildingName","图书馆");input.put("locationDetail","一层研讨区（演示配置）");input.put("capacity",30);input.put("availableEquipment","投影设备");input.put("rules","现场核对后使用");input.put("enabled",true);input.put("managerUserIds",java.util.List.of(reviewer()));return input;}
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
