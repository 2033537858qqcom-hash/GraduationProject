package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:service-closure-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo.enabled=true",
        "app.agent.deepseek.enabled=false"
    }
)
class ServiceClosureIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired cn.zzuit.campus.affairs.ServiceFollowupService followups;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void capacityIsCheckedAtSubmissionAndApproval() throws Exception {
        var student=login("20260001"); var reviewer=login("affairs1001"); long venue=venue(student);
        try {
            ok(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("capacity",5)));
            assertThat(student.send("POST","/api/reservations/submit",booking(venue,6,81)).statusCode()).isEqualTo(409);
            var pending=ok(student.send("POST","/api/reservations/submit",booking(venue,5,82)));
            ok(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("capacity",4)));
            assertThat(reviewer.send("POST","/api/reservations/"+id(pending)+"/review",Map.of("action","APPROVE")).statusCode()).isEqualTo(409);
            assertThat(ok(student.get("/api/reservations/"+id(pending))).path("status").asText()).isEqualTo("SUBMITTED");
            assertThat(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("capacity",0)).statusCode()).isEqualTo(400);
        } finally { ok(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("capacity",200))); }
    }

    @Test
    void disabledVenueCannotBeApprovedEvenWhenDisabledOutsideManagement() throws Exception {
        var student=login("20260001"); var reviewer=login("affairs1001"); long venue=venue(student);
        var pending=ok(student.send("POST","/api/reservations/submit",booking(venue,1,83)));
        try {
            jdbc.sql("UPDATE venue_resource SET enabled=FALSE WHERE id=:v").param("v",venue).update();
            assertThat(reviewer.send("POST","/api/reservations/"+id(pending)+"/review",Map.of("action","APPROVE")).statusCode()).isEqualTo(409);
        } finally { jdbc.sql("UPDATE venue_resource SET enabled=TRUE WHERE id=:v").param("v",venue).update(); }
    }

    @Test
    void disablingVenueCancelsUnstartedReservationsAndInvalidatesPass() throws Exception {
        var student=login("20260001"); var reviewer=login("affairs1001"); long venue=venue(student);
        var approved=approve(student,reviewer,venue,84);
        try {
            ok(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("enabled",false)));
            var cancelled=ok(student.get("/api/reservations/"+id(approved)));
            assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled.path("actions").toString()).contains("RESOURCE_DISABLED");
            assertThat(student.get("/api/reservations/"+id(approved)+"/pass").statusCode()).isEqualTo(409);
        } finally { ok(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("enabled",true))); }
    }

    @Test
    void reservationUseAndCompletionRequireResponsibleReviewerTimeVersionAndAudit() throws Exception {
        var student=login("20260001"); var reviewer=login("affairs1001"); long venue=venue(student);
        var approved=approve(student,reviewer,venue,85); long id=id(approved);
        assertThat(use(reviewer,id,approved,"START").statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE venue_reservation SET starts_at=:s,ends_at=:e WHERE id=:id")
            .param("s",LocalDateTime.now().minusMinutes(5)).param("e",LocalDateTime.now().plusMinutes(55)).param("id",id).update();
        assertThat(use(student,id,approved,"START").statusCode()).isEqualTo(403);
        var using=ok(use(reviewer,id,approved,"START")); assertThat(using.path("status").asText()).isEqualTo("IN_USE");
        assertThat(use(reviewer,id,approved,"COMPLETE").statusCode()).isEqualTo(409);
        assertThat(student.send("POST","/api/reservations/"+id+"/cancel",Map.of("reason","test")).statusCode()).isEqualTo(409);
        assertThat(reviewer.send("PUT","/api/reservations/venues/"+venue,Map.of("enabled",false)).statusCode()).isEqualTo(409);
        assertThat(ok(reviewer.get("/api/workbench/tasks?domain=reservation")).toString()).contains("IN_USE");
        var closed=ok(use(reviewer,id,using,"COMPLETE")); assertThat(closed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(student.get("/api/reservations/"+id+"/pass").statusCode()).isEqualTo(409);
        assertThat(ok(student.get("/api/affairs/mine/RESERVATION/"+id+"/timeline")).path("items").toString()).contains("COMPLETE");
    }

    @Test
    void returnReportAndCounselorClosureAreScopedVersionedAndTracked() throws Exception {
        var student=login("20260001"); var counselor=login("counselor1001");
        var leave=approvedLeave(student,counselor,91); long id=id(leave);
        assertThat(report(student,leave).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE leave_request SET starts_at=:s,ends_at=:e WHERE id=:id").param("s",LocalDateTime.now().minusHours(2)).param("e",LocalDateTime.now().plusHours(2)).param("id",id).update();
        assertThat(report(login("20260002"),leave).statusCode()).isEqualTo(404);
        var pending=ok(report(student,leave)); assertThat(pending.path("status").asText()).isEqualTo("RETURN_PENDING");
        assertThat(ok(counselor.get("/api/workbench/tasks?domain=leave")).toString()).contains("RETURN_PENDING");
        assertThat(close(counselor,leave,"CONFIRM").statusCode()).isEqualTo(409);
        assertThat(close(student,pending,"CONFIRM").statusCode()).isEqualTo(403);
        var completed=ok(close(counselor,pending,"CONFIRM")); assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(ok(student.get("/api/affairs/mine/LEAVE/"+id+"/timeline")).path("items").toString()).contains("RETURN_CONFIRMED");
        var end=jdbc.sql("SELECT ends_at FROM leave_calendar WHERE request_id=:id").param("id",id).query(LocalDateTime.class).single();
        assertThat(end).isBefore(LocalDateTime.now().plusMinutes(1));
    }

    @Test
    void returnedClosureCanBeReportedAgainAndUnrelatedReviewerCannotClose() throws Exception {
        var student=login("20260001"); var counselor=login("counselor1001");
        var leave=approvedLeave(student,counselor,92); long id=id(leave);
        jdbc.sql("UPDATE leave_request SET starts_at=:s,ends_at=:e WHERE id=:id").param("s",LocalDateTime.now().minusHours(2)).param("e",LocalDateTime.now().plusHours(2)).param("id",id).update();
        var pending=ok(report(student,leave));
        var rejected=ok(close(counselor,pending,"RETURN")); assertThat(rejected.path("status").asText()).isEqualTo("APPROVED");
        var second=ok(report(student,rejected));
        assertThat(second.path("version").asInt()).isGreaterThan(pending.path("version").asInt());
        ok(close(counselor,second,"CONFIRM"));
    }

    @Test
    void expiredUnusedBookingsCloseButActiveUseRemainsForHumanHandover() throws Exception {
        var student=login("20260001"); var reviewer=login("affairs1001"); long venue=venue(student);
        var approved=approve(student,reviewer,venue,86);
        var pending=ok(student.send("POST","/api/reservations/submit",booking(venue,1,87)));
        var active=approve(student,reviewer,venue,88);
        jdbc.sql("UPDATE venue_reservation SET starts_at=:s,ends_at=:e WHERE id=:id")
            .param("s",LocalDateTime.now().minusMinutes(5)).param("e",LocalDateTime.now().plusMinutes(55)).param("id",id(active)).update();
        var using=ok(use(reviewer,id(active),active,"START"));
        try {
            for (var record:List.of(approved,pending,active)) jdbc.sql("UPDATE venue_reservation SET starts_at=:s,ends_at=:e WHERE id=:id")
                .param("s",LocalDateTime.now().minusHours(2)).param("e",LocalDateTime.now().minusHours(1)).param("id",id(record)).update();
            followups.processDue(); followups.processDue();
            assertThat(ok(student.get("/api/reservations/"+id(approved))).path("status").asText()).isEqualTo("EXPIRED");
            assertThat(ok(student.get("/api/reservations/"+id(pending))).path("status").asText()).isEqualTo("EXPIRED");
            assertThat(ok(student.get("/api/reservations/"+id(active))).path("status").asText()).isEqualTo("IN_USE");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation_action WHERE reservation_id=:id AND action='EXPIRE' AND actor_id IS NULL")
                .param("id",id(approved)).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM service_followup_reminder WHERE kind='RESERVATION' AND request_id=:id")
                .param("id",id(active)).query(Long.class).single()).isEqualTo(2);
        } finally { ok(use(reviewer,id(active),using,"COMPLETE")); }
    }

    @Test
    void overdueLeaveRemindsStudentAndCurrentCounselorOnlyOncePerDay() throws Exception {
        var student=login("20260001"); var counselor=login("counselor1001");
        var leave=approvedLeave(student,counselor,93); long id=id(leave);
        jdbc.sql("UPDATE leave_request SET starts_at=:s,ends_at=:e WHERE id=:id")
            .param("s",LocalDateTime.now().minusHours(3)).param("e",LocalDateTime.now().minusHours(1)).param("id",id).update();
        followups.processDue(); followups.processDue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM service_followup_reminder WHERE kind='LEAVE' AND request_id=:id")
            .param("id",id).query(Long.class).single()).isEqualTo(2);
        assertThat(ok(student.get("/api/leave/mine/"+id)).path("status").asText()).isEqualTo("APPROVED");
        var pending=ok(report(student,leave)); ok(close(counselor,pending,"CONFIRM"));
    }

    private long venue(Browser b) throws Exception { return ok(b.get("/api/reservations/venues?placeId=east-gym")).get(0).path("id").asLong(); }
    private long id(JsonNode n) { return n.path("id").asLong(); }
    private Map<String,Object> booking(long venue,int people,int days) {
        var start=LocalDateTime.now().plusDays(days).withHour(10).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        return Map.of("requestKey","CLOSE-"+UUID.randomUUID(),"venueId",venue,"activityTheme","Closure test","activityType","SPORTS","expectedParticipants",people,
            "contactPhone","13800138000","startsAt",start.toString(),"endsAt",start.plusHours(1).toString());
    }
    private JsonNode approve(Browser student,Browser reviewer,long venue,int day) throws Exception {
        var r=ok(student.send("POST","/api/reservations/submit",booking(venue,1,day)));
        return ok(reviewer.send("POST","/api/reservations/"+id(r)+"/review",Map.of("action","APPROVE")));
    }
    private HttpResponse<String> use(Browser b,long id,JsonNode r,String action) throws Exception {
        return b.send("POST","/api/reservations/"+id+"/usage",Map.of("action",action,"version",r.path("version").asInt(),"comment","Verified by responsible staff"));
    }
    private JsonNode approvedLeave(Browser s,Browser c,int days) throws Exception {
        var start=LocalDateTime.now().plusDays(days).withHour(10).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var draft=ok(s.send("POST","/api/leave/mine",Map.of("requestKey",UUID.randomUUID().toString(),"reasonType","PERSONAL","reason","Closure test",
            "contact","13800138000","startsAt",start.toString(),"endsAt",start.plusHours(2).toString(),"version",0)));
        var submitted=ok(s.send("POST","/api/leave/mine/"+id(draft)+"/submit",Map.of("version",draft.path("version").asInt(),"comment","submit")));
        return ok(c.send("POST","/api/leave/review/"+id(draft)+"/decision",Map.of("version",submitted.path("version").asInt(),"action","APPROVE","comment","approved")));
    }
    private HttpResponse<String> report(Browser s,JsonNode r) throws Exception { return s.send("POST","/api/leave/mine/"+id(r)+"/return",Map.of("version",r.path("version").asInt(),"comment","I have returned")); }
    private HttpResponse<String> close(Browser c,JsonNode r,String action) throws Exception { return c.send("POST","/api/leave/review/"+id(r)+"/close",Map.of("version",r.path("version").asInt(),"action",action,"comment","Verified return")); }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Browser login(String name) throws Exception {
        var browser = new Browser();
        browser.csrf();
        ok(browser.send("POST", "/api/auth/login", Map.of("username", name, "password", "Demo@2026")));
        browser.csrf();
        return browser;
    }

    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String header, token;

        HttpRequest.Builder builder(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            var data = json.readTree(get("/api/auth/csrf").body());
            header = data.path("headerName").asText();
            token = data.path("token").asText();
        }

        HttpResponse<String> send(String method, String path, Map<String, ?> body) throws Exception {
            return http.send(
                builder(path)
                    .header(header, token)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
        }
    }
}
