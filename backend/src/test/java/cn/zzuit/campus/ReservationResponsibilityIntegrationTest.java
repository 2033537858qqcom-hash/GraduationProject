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
    "spring.datasource.url=jdbc:h2:mem:reservation-responsibility-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true", "app.agent.deepseek.enabled=false"
})
class ReservationResponsibilityIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ReservationDemoInitializer initializer;
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void soleReviewerCannotSubmitOwnApplicationAndDraftIsPreserved() throws Exception {
        var reviewer = staff();
        long venue = venue();
        bind(venue, reviewer.id());
        var form = form(venue);
        var draft = ok(reviewer.browser().send("POST", "/api/reservations/draft", form));
        var response = reviewer.browser().send("POST", "/api/reservations/submit", form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains("NO_VENUE_REVIEWER");
        var preserved = ok(reviewer.browser().get("/api/reservations/" + draft.path("id").asLong()));
        assertThat(preserved.path("status").asText()).isEqualTo("DRAFT");
        assertThat(preserved.path("version").asInt()).isEqualTo(draft.path("version").asInt());
        assertThat(preserved.path("actions").size()).isEqualTo(1);
    }

    @Test
    void dualRoleApplicantsAreExcludedBeforeCountingAndPagingReviewTasks() throws Exception {
        var applicant = staff();
        var other = staff();
        long venue = venue();
        bind(venue, applicant.id());
        bind(venue, other.id());
        var submitted = ok(applicant.browser().send("POST", "/api/reservations/submit", form(venue)));
        long id = submitted.path("id").asLong();
        assertThat(ok(applicant.browser().get("/api/reservations/review?status=SUBMITTED&pageSize=1")).path("total").asLong()).isZero();
        var tasks = ok(applicant.browser().get("/api/workbench/tasks?domain=reservation"));
        assertThat(tasks.path("domainCounts").path("reservation").asLong()).isZero();
        assertThat(tasks.path("tasks").size()).isZero();
        var otherTasks = ok(other.browser().get("/api/reservations/review?status=SUBMITTED&pageSize=1"));
        assertThat(otherTasks.path("total").asLong()).isEqualTo(1);
        assertThat(otherTasks.path("items").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(applicant.browser().send("POST", "/api/reservations/" + id + "/review", Map.of("action", "APPROVE", "comment", "自审")).statusCode()).isEqualTo(403);
        assertThat(ok(other.browser().send("POST", "/api/reservations/" + id + "/review", Map.of("action", "APPROVE", "comment", "核验通过"))).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void handoffMustKeepAnotherEligibleReviewerAndNotifiesNewResponsiblePersonOnce() throws Exception {
        var manager = login("affairs1001");
        var applicant = staff();
        var oldReviewer = staff();
        var newReviewer = staff();
        long venue = venue();
        bind(venue, applicant.id());
        bind(venue, oldReviewer.id());
        long id = ok(applicant.browser().send("POST", "/api/reservations/submit", form(venue))).path("id").asLong();
        var rejected = manager.send("DELETE", "/api/reservations/venues/" + venue + "/managers/" + oldReviewer.id(), Map.of());
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
        assertThat(rejected.body()).contains("REVIEWER_HANDOFF_REQUIRED");
        assertThat(ok(oldReviewer.browser().get("/api/reservations/" + id)).path("status").asText()).isEqualTo("SUBMITTED");
        ok(manager.send("POST", "/api/reservations/venues/" + venue + "/managers", Map.of("userId", newReviewer.id())));
        ok(manager.send("POST", "/api/reservations/venues/" + venue + "/managers", Map.of("userId", newReviewer.id())));
        assertThat(notices(newReviewer.id(), id)).isEqualTo(1);
        ok(manager.send("DELETE", "/api/reservations/venues/" + venue + "/managers/" + oldReviewer.id(), Map.of()));
        assertThat(oldReviewer.browser().get("/api/reservations/" + id).statusCode()).isEqualTo(403);
        assertThat(ok(newReviewer.browser().get("/api/reservations/" + id)).path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(ok(newReviewer.browser().get("/api/reservations/review?status=SUBMITTED")).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void overlappingRolesDoNotDuplicateNotifications() throws Exception {
        var reviewer = staff();
        String role = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO app_role(code,name) VALUES(:r,'测试叠加权限')").param("r", role).update();
        jdbc.sql("INSERT INTO role_permission(role_code,permission_code) VALUES(:r,'reservation:review')").param("r", role).update();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,:r)").param("u", reviewer.id()).param("r", role).update();
        long venue = venue();
        bind(venue, reviewer.id());
        long id = ok(login("20260001").send("POST", "/api/reservations/submit", form(venue))).path("id").asLong();
        assertThat(notices(reviewer.id(), id)).isEqualTo(1);
    }

    @Test
    void approvedApplicationCannotBeRewrittenBySubmittingTheSameRequestKey() throws Exception {
        var applicant = login("20260001");
        var reviewer = staff();
        long venue = venue();
        bind(venue, reviewer.id());
        var form = form(venue);
        long id = ok(applicant.send("POST", "/api/reservations/submit", form)).path("id").asLong();
        var approved = ok(reviewer.browser().send("POST", "/api/reservations/" + id + "/review", Map.of("action", "APPROVE", "comment", "同意")));
        var changed = new java.util.HashMap<String, Object>(form);
        changed.put("startsAt", LocalDateTime.parse(form.get("startsAt").toString()).plusDays(1).toString());
        changed.put("endsAt", LocalDateTime.parse(form.get("endsAt").toString()).plusDays(1).toString());
        var response = applicant.send("POST", "/api/reservations/submit", changed);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains("RESERVATION_IMMUTABLE");
        var preserved = ok(applicant.get("/api/reservations/" + id));
        assertThat(preserved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(preserved.path("version").asInt()).isEqualTo(approved.path("version").asInt());
        assertThat(preserved.path("startsAt")).isEqualTo(approved.path("startsAt"));
        assertThat(preserved.path("actions").size()).isEqualTo(2);
        assertThat(notices(reviewer.id(), id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void managementShowsInvalidReviewersAndUnattendedWorkWithoutExposingApplications(boolean disableAccount) throws Exception {
        var manager = login("affairs1001");
        var reviewer = staff();
        long venue = venue();
        bind(venue, reviewer.id());
        long id = ok(login("20260001").send("POST", "/api/reservations/submit", form(venue))).path("id").asLong();
        // Emergency revocation stays effective; resource managers see the resulting configuration gap.
        if (disableAccount) {
            jdbc.sql("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=:u").param("u", reviewer.id()).update();
        } else {
            jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='STUDENT_AFFAIRS'").param("u", reviewer.id()).update();
        }
        var item = managed(manager, venue);
        assertThat(item.path("activeReviewerCount").asInt()).isZero();
        assertThat(item.path("pendingReviewCount").asInt()).isEqualTo(1);
        assertThat(item.path("unattendedReviewCount").asInt()).isEqualTo(1);
        assertThat(item.path("managers").get(0).path("eligible").asBoolean()).isFalse();
        assertThat(item.has("applications")).isFalse();
        assertThat(manager.get("/api/reservations/" + id).statusCode()).isEqualTo(403);
        var replacement = staff();
        ok(manager.send("POST", "/api/reservations/venues/" + venue + "/managers", Map.of("userId", replacement.id())));
        assertThat(managed(manager, venue).path("unattendedReviewCount").asInt()).isZero();
        assertThat(notices(replacement.id(), id)).isEqualTo(1);
    }

    @Test
    void losingApplicationPermissionAlsoRevokesCancellation() throws Exception {
        var applicant = staff();
        var reviewer = staff();
        long venue = venue();
        bind(venue, reviewer.id());
        long id = ok(applicant.browser().send("POST", "/api/reservations/submit", form(venue))).path("id").asLong();
        jdbc.sql("DELETE FROM user_role WHERE user_id=:u AND role_code='TEACHER'").param("u", applicant.id()).update();
        var response = applicant.browser().send("POST", "/api/reservations/" + id + "/cancel", Map.of("reason", "权限已撤销"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(ok(reviewer.browser().get("/api/reservations/" + id)).path("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void restartingDemoDoesNotRestoreRemovedResponsibility() {
        long reviewer = jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single();
        long venue = jdbc.sql("SELECT id FROM venue_resource WHERE code='VEN-LIB-101'").query(Long.class).single();
        jdbc.sql("DELETE FROM venue_manager WHERE venue_id=:v AND user_id=:u").param("v", venue).param("u", reviewer).update();
        initializer.run(new DefaultApplicationArguments());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_manager WHERE venue_id=:v AND user_id=:u")
            .param("v", venue).param("u", reviewer).query(Long.class).single()).isZero();
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
