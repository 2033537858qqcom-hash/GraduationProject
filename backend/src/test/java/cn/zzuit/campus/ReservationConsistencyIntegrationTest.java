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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:reservation-consistency-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true", "app.agent.deepseek.enabled=false", "app.services.followup.enabled=false"
})
class ReservationConsistencyIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ReservationDemoInitializer initializer;
    final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings={"APPROVED","IN_USE"})
    void removingOnlyOwnerRequiresHandoffForAllUnfinishedStages(String state) throws Exception {
        var manager=login("affairs1001"); var owner=staff(); long v=venue(); bind(v,owner.id());
        var record=record(login("20260001"),owner.browser(),v,state,21);
        var response=manager.send("DELETE","/api/reservations/venues/"+v+"/managers/"+owner.id(),Map.of());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains("REVIEWER_HANDOFF_REQUIRED");
        assertThat(ok(owner.browser().get("/api/reservations/"+id(record))).path("status").asText()).isEqualTo(state);
        var next=staff(); ok(manager.send("POST","/api/reservations/venues/"+v+"/managers",Map.of("userId",next.id())));
        ok(manager.send("DELETE","/api/reservations/venues/"+v+"/managers/"+owner.id(),Map.of()));
        assertThat(owner.browser().get("/api/reservations/"+id(record)).statusCode()).isEqualTo(403);
        if(state.equals("IN_USE")) assertThat(ok(use(next.browser(),record,"COMPLETE")).path("status").asText()).isEqualTo("COMPLETED");
        else assertThat(ok(next.browser().get("/api/reservations/"+id(record))).path("status").asText()).isEqualTo("APPROVED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"APPROVED,true","APPROVED,false","IN_USE,true","IN_USE,false"})
    void emergencyRevocationShowsAllOrphanedStagesAndReplacementCanRecover(String state,boolean disable) throws Exception {
        var manager=login("affairs1001"); var owner=staff(); long v=venue(); bind(v,owner.id());
        var record=record(login("20260001"),owner.browser(),v,state,22);
        if(disable) jdbc.sql("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=:u").param("u",owner.id()).update();
        else jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='STUDENT_AFFAIRS'").param("u",owner.id()).update();
        var item=managed(manager,v);
        assertThat(item.path("activeReviewerCount").asInt(-1)).isZero();
        assertThat(item.path("pendingReviewCount").asInt(-1)).isZero();
        assertThat(item.path(state.equals("APPROVED")?"pendingUsageCount":"activeUseCount").asInt(-1)).isEqualTo(1);
        assertThat(item.path("unattendedTaskCount").asInt(-1)).isEqualTo(1);
        assertThat(item.has("applications")).isFalse();
        assertThat(manager.get("/api/reservations/"+id(record)).statusCode()).isEqualTo(403);
        var next=staff(); ok(manager.send("POST","/api/reservations/venues/"+v+"/managers",Map.of("userId",next.id())));
        assertThat(managed(manager,v).path("unattendedTaskCount").asInt(-1)).isZero();
        assertThat(notices(next.id(),id(record))).isEqualTo(1);
        if(state.equals("IN_USE")) ok(use(next.browser(),record,"COMPLETE"));
    }

    @Test void newOwnerReceivesEachUnfinishedStageOnceButNeverOwnApplication() throws Exception {
        var manager=login("affairs1001"); var owner=staff(); var next=staff(); long v=venue(); bind(v,owner.id());
        var student=login("20260001");
        var submitted=record(student,owner.browser(),v,"SUBMITTED",23);
        var approved=record(student,owner.browser(),v,"APPROVED",24);
        var active=record(student,owner.browser(),v,"IN_USE",25);
        var own=record(next.browser(),owner.browser(),v,"APPROVED",26);
        ok(manager.send("POST","/api/reservations/venues/"+v+"/managers",Map.of("userId",next.id())));
        ok(manager.send("POST","/api/reservations/venues/"+v+"/managers",Map.of("userId",next.id())));
        for(var r:java.util.List.of(submitted,approved,active)) assertThat(notices(next.id(),id(r))).isEqualTo(1);
        assertThat(notices(next.id(),id(own))).isZero();
        var item=managed(manager,v);
        assertThat(item.path("pendingReviewCount").asInt(-1)).isEqualTo(1);
        assertThat(item.path("pendingUsageCount").asInt(-1)).isEqualTo(2);
        assertThat(item.path("activeUseCount").asInt(-1)).isEqualTo(1);
        ok(use(next.browser(),active,"COMPLETE"));
    }

    @ParameterizedTest @ValueSource(strings={"APPROVED","IN_USE"})
    void applicantCannotBeTheOnlyRemainingOwner(String state) throws Exception {
        var manager=login("affairs1001"); var applicant=staff(); var owner=staff(); long v=venue();
        bind(v,owner.id());bind(v,applicant.id()); var record=record(applicant.browser(),owner.browser(),v,state,27);
        assertThat(manager.send("DELETE","/api/reservations/venues/"+v+"/managers/"+owner.id(),Map.of()).statusCode()).isEqualTo(409);
        if(state.equals("IN_USE")) ok(use(owner.browser(),record,"COMPLETE"));
    }

    @Test void expiredApprovedAndCompletedRecordsDoNotPreventRemovingOwner() throws Exception {
        var manager=login("affairs1001"); var owner=staff(); long v=venue();bind(v,owner.id());
        var approved=record(login("20260001"),owner.browser(),v,"APPROVED",28);
        jdbc.sql("UPDATE venue_reservation SET starts_at=:s,ends_at=:e WHERE id=:id")
            .param("s",LocalDateTime.now().minusHours(2)).param("e",LocalDateTime.now().minusHours(1)).param("id",id(approved)).update();
        var active=record(login("20260001"),owner.browser(),v,"IN_USE",29);ok(use(owner.browser(),active,"COMPLETE"));
        var item=managed(manager,v);
        assertThat(item.path("pendingUsageCount").asInt(-1)).isZero();
        assertThat(item.path("activeUseCount").asInt(-1)).isZero();
        ok(manager.send("DELETE","/api/reservations/venues/"+v+"/managers/"+owner.id(),Map.of()));
    }

    @ParameterizedTest @ValueSource(strings={"APPROVE","RETURN","REJECT"})
    void staleDecisionCannotProcessResubmittedMaterials(String action) throws Exception {
        var student=login("20260001");var owner=staff();long v=venue();bind(v,owner.id());
        var form=new java.util.HashMap<String,Object>(form(v));
        var original=ok(student.send("POST","/api/reservations/submit",form));long id=id(original);
        ok(decide(owner.browser(),original,"RETURN"));form.put("activityTheme","Changed application");form.put("expectedParticipants",19);
        var latest=ok(student.send("POST","/api/reservations/submit",form));
        long priorActions=jdbc.sql("SELECT COUNT(*) FROM venue_reservation_action WHERE reservation_id=:id").param("id",id).query(Long.class).single();
        var rejected=decide(owner.browser(),original,action);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
        assertThat(rejected.body()).contains("RESERVATION_VERSION_CHANGED");
        var unchanged=ok(owner.browser().get("/api/reservations/"+id));
        assertThat(unchanged.path("version").asInt()).isEqualTo(latest.path("version").asInt());
        assertThat(unchanged.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation_action WHERE reservation_id=:id").param("id",id).query(Long.class).single()).isEqualTo(priorActions);
        assertThat(ok(decide(owner.browser(),latest,action)).path("status").asText()).isEqualTo(action.equals("APPROVE")?"APPROVED":action.equals("RETURN")?"RETURNED":"REJECTED");
    }

    @ParameterizedTest @ValueSource(strings={"MISSING","NULL","NEGATIVE"})
    void reviewRequiresAnExplicitNonnegativeSeenVersion(String input) throws Exception {
        var owner=staff();long v=venue();bind(v,owner.id());var record=record(login("20260001"),owner.browser(),v,"SUBMITTED",32);
        var body=new java.util.HashMap<String,Object>();body.put("action","APPROVE");
        if(input.equals("NULL"))body.put("version",null);if(input.equals("NEGATIVE"))body.put("version",-1);
        var response=owner.browser().send("POST","/api/reservations/"+id(record)+"/review",body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(ok(owner.browser().get("/api/reservations/"+id(record))).path("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test void duplicateReviewDoesNotCreateAnotherDecision() throws Exception {
        var owner=staff();long v=venue();bind(v,owner.id());var record=record(login("20260001"),owner.browser(),v,"SUBMITTED",33);
        var approved=ok(decide(owner.browser(),record,"APPROVE"));
        assertThat(decide(owner.browser(),record,"APPROVE").statusCode()).isEqualTo(409);
        assertThat(ok(owner.browser().get("/api/reservations/"+id(record))).path("actions").size()).isEqualTo(approved.path("actions").size());
    }

    private long id(JsonNode r){return r.path("id").asLong();}
    private HttpResponse<String> decide(Browser owner,JsonNode r,String action)throws Exception{
        return owner.send("POST","/api/reservations/"+id(r)+"/review",Map.of("version",r.path("version").asInt(),"action",action,"comment","Checked materials"));
    }
    private HttpResponse<String> use(Browser owner,JsonNode r,String action)throws Exception{
        return owner.send("POST","/api/reservations/"+id(r)+"/usage",Map.of("version",r.path("version").asInt(),"action",action,"comment","Checked handover"));
    }
    private JsonNode record(Browser student,Browser owner,long v,String state,int day)throws Exception{
        var form=new java.util.HashMap<String,Object>(form(v));var start=LocalDateTime.now().plusDays(day).withHour(10).withMinute(0).withSecond(0);
        form.put("startsAt",start.toString());form.put("endsAt",start.plusHours(1).toString());
        var r=ok(student.send("POST","/api/reservations/submit",form));if(state.equals("SUBMITTED"))return r;
        r=ok(decide(owner,r,"APPROVE"));if(state.equals("APPROVED"))return r;
        jdbc.sql("UPDATE venue_reservation SET starts_at=:s,ends_at=:e WHERE id=:id").param("s",LocalDateTime.now().minusMinutes(5)).param("e",LocalDateTime.now().plusMinutes(55)).param("id",id(r)).update();
        return ok(use(owner,r,"START"));
    }

    private JsonNode managed(Browser browser, long venue) throws Exception {
        for (var item : ok(browser.get("/api/reservations/venues/manage"))) if (item.path("id").asLong() == venue) return item;
        throw new AssertionError("missing resource " + venue);
    }

    private long notices(long user, long reservation) {
        return jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE user_id=:u AND source_type='RESERVATION' AND source_id=:r AND source_view='REVIEW'")
            .param("u", user).param("r", reservation).query(Long.class).single();
    }

    private Staff staff() throws Exception {
        String username = "scope_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
            INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo)
            SELECT :name,password_hash,'资源负责测试人员',:name,campus_id,department_id,TRUE
            FROM app_user WHERE username='affairs1001'
            """).param("name", username).update();
        long id = jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name", username).query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'STUDENT_AFFAIRS'),(:u,'TEACHER')").param("u", id).update();
        return new Staff(id, login(username));
    }

    private long venue() {
        String code = "TEST-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO venue_resource(campus_id,code,name,location_detail) VALUES(1,:c,'测试资源','隔离测试区')").param("c", code).update();
        return jdbc.sql("SELECT id FROM venue_resource WHERE code=:c").param("c", code).query(Long.class).single();
    }

    private void bind(long venue, long user) {
        jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v", venue).param("u", user).update();
    }

    private Map<String, Object> form(long venue) {
        var start = LocalDateTime.now().plusDays(10).withHour(10).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        return Map.of("requestKey", UUID.randomUUID().toString(), "venueId", venue, "activityTheme", "隔离测试活动",
            "activityType", "ACADEMIC", "expectedParticipants", 10, "contactPhone", "13800138001",
            "startsAt", start.toString(), "endsAt", start.plusHours(1).toString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private Browser login(String name) throws Exception {
        var browser = new Browser();
        browser.csrf();
        ok(browser.send("POST", "/api/auth/login", Map.of("username", name, "password", "Demo@2026")));
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
