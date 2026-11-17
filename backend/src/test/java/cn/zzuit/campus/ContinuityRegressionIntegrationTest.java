package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        "spring.datasource.url=jdbc:h2:mem:continuity-regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo.enabled=true",
        "app.agent.deepseek.enabled=false"
    }
)
class ContinuityRegressionIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void dormManagerHasAgentQueryAndSampleCertificateIsVerifiable() throws Exception {
        var dorm = login("dorm1001");
        var caps = ok(dorm.get("/api/assistant/capabilities"));
        assertThat(caps.path("tools").toString()).contains("my_review_pending", "my_notice_summary", "search_guides");

        var verify = ok(new Browser().get("/api/public/certificates/verify?number=DEMO-20261017-000001"));
        assertThat(verify.path("valid").asBoolean()).isTrue();
        assertThat(verify.path("documentNumber").asText()).isEqualTo("DEMO-20261017-000001");
    }

    @Test
    void workbenchAggregatesPendingRepairCancellationsHousingCancelRequestsAndDeepLinks() throws Exception {
        var student = login("20260001");
        var affairs = login("affairs1001");
        var dispatcher = login("dispatcher1001");
        var technician = login("technician1001");
        var dorm = login("dorm1001");
        var editor = login("editor");
        var reviewer = login("reviewer");

        // 1. Certificate deep link includes ?id=
        var cert = ok(student.post("/api/certificates/mine", Map.of(
            "requestKey", key(),
            "purpose", "工作台深链回归测试",
            "deliveryMethod", "DIGITAL",
            "identityConfirmed", true,
            "version", 0
        )));
        long certId = cert.path("id").asLong();
        ok(student.post("/api/certificates/mine/" + certId + "/submit", Map.of("version", 0, "comment", "提交")));
        var affairsTasks = ok(affairs.get("/api/workbench/tasks?domain=CERTIFICATE&pageSize=50")).path("tasks").toString();
        assertThat(affairsTasks).contains("/student-affairs/certificates?id=" + certId + "&tab=details");

        // 2. Repair pending cancellation appears in dispatch workbench with ?id=
        long locId = jdbc.sql("SELECT id FROM repair_location WHERE enabled=TRUE AND kind='CAMPUS_PUBLIC' ORDER BY id LIMIT 1").query(Long.class).single();
        var repair = ok(student.post("/api/repairs/mine", Map.of(
            "requestKey", key(),
            "locationId", locId,
            "category", "ELECTRICITY",
            "title", "工作台取消测试灯具",
            "description", "测试报修取消进入工作台",
            "contact", "13900001111",
            "contactWindow", "工作时间",
            "version", 0
        )));
        long repairId = repair.path("id").asLong();
        var subRepair = ok(student.post("/api/repairs/mine/" + repairId + "/submit", Map.of("requestKey", key(), "version", 0, "comment", "提交")));
        long techId = jdbc.sql("SELECT id FROM app_user WHERE username='technician1001'").query(Long.class).single();
        var assigned = ok(dispatcher.post("/api/repairs/dispatch/" + repairId + "/assign", Map.of(
            "requestKey", key(),
            "version", subRepair.path("version").asInt(),
            "technicianId", techId,
            "priority", "NORMAL",
            "comment", "派单"
        )));
        ok(student.post("/api/repairs/mine/" + repairId + "/cancellations", Map.of(
            "requestKey", key(),
            "version", assigned.path("version").asInt(),
            "comment", "临时不需要维修"
        )));
        var dispatchTasks = ok(dispatcher.get("/api/workbench/tasks?domain=REPAIR_DISPATCH&pageSize=50")).path("tasks").toString();
        assertThat(dispatchTasks).contains("取消待处理", "/logistics/repairs/dispatch?id=" + repairId + "&tab=details");

        // 3. Content notice + lowercase category label on workbench
        var content = ok(editor.post("/api/admin/content", Map.of(
            "slug", "continuity-notice-" + UUID.randomUUID().toString().substring(0, 6),
            "category", "notice",
            "title", "连贯性通知测试公告",
            "summary", "连贯性测试摘要",
            "body", "连贯性测试正文",
            "sourceType", "DEMO",
            "version", 0
        )));
        long contentId = content.path("id").asLong();
        ok(editor.post("/api/admin/content/" + contentId + "/submit", Map.of("version", 0)));
        var contentTasks = ok(reviewer.get("/api/workbench/tasks?domain=CONTENT&pageSize=50")).path("tasks").toString();
        assertThat(contentTasks).contains("综合通知");
        assertThat(ok(reviewer.get("/api/notices")).path("items").toString()).contains("/admin/content");

        // 4. Housing approval notifies dorm manager; cancel-request surfaces in workbench with ?id=
        var transfer = ok(student.post("/api/housing/transfers/mine", Map.of(
            "requestKey", key(),
            "version", 0,
            "reason", "连贯性调宿测试",
            "preferredArea", "东校区",
            "materialNote", "已备齐说明"
        )));
        long transferId = transfer.path("id").asLong();
        var subTransfer = ok(student.post("/api/housing/transfers/mine/" + transferId + "/submit", Map.of(
            "requestKey", key(),
            "version", 0,
            "comment", "提交调宿"
        )));
        long dormNoticesBefore = jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE source_type='HOUSING' AND source_id=:id AND source_view='ASSIGN'")
            .param("id", transferId).query(Long.class).single();
        var approvedTransfer = ok(affairs.post("/api/housing/transfers/review/" + transferId + "/approve", Map.of(
            "requestKey", key(),
            "version", subTransfer.path("version").asInt(),
            "comment", "同意初审"
        )));
        long dormNoticesAfter = jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE source_type='HOUSING' AND source_id=:id AND source_view='ASSIGN'")
            .param("id", transferId).query(Long.class).single();
        assertThat(dormNoticesAfter).isGreaterThan(dormNoticesBefore);

        long vacantBed = jdbc.sql("""
            SELECT d.id FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id
            WHERE d.status='VACANT' AND d.enabled=TRUE AND r.enabled=TRUE AND b.enabled=TRUE
            AND b.id IN (SELECT building_id FROM dorm_manager_scope WHERE enabled=TRUE)
            ORDER BY d.id LIMIT 1
            """).query(Long.class).single();
        var reserved = ok(dorm.post("/api/housing/transfers/assign/" + transferId + "/reserve", Map.of(
            "requestKey", key(),
            "version", approvedTransfer.path("version").asInt(),
            "bedId", vacantBed,
            "reason", "预留床位"
        )));
        ok(student.post("/api/housing/transfers/mine/" + transferId + "/cancel-request", Map.of(
            "requestKey", key(),
            "version", reserved.path("version").asInt(),
            "comment", "暂不调宿"
        )));
        var dormTasks = ok(dorm.get("/api/workbench/tasks?domain=HOUSING_ASSIGN&pageSize=50")).path("tasks").toString();
        assertThat(dormTasks).contains("取消预留待处理", "/housing/assignments?id=" + transferId + "&tab=details");
    }

    @Test
    void reservationNoticesRevalidateManagerAccessAndTeacherCanRunReservationMission() throws Exception {
        var teacher = login("teacher1001");
        var affairs = login("affairs1001");
        long venueId = jdbc.sql("SELECT id FROM venue_resource WHERE enabled=TRUE AND category='VENUE' ORDER BY id LIMIT 1").query(Long.class).single();
        var start = LocalDateTime.now().plusDays(10).withHour(14).withMinute(0).withSecond(0).withNano(0);
        var end = start.plusHours(2);
        var submitted = ok(teacher.post("/api/reservations/submit", Map.of(
            "requestKey", key(),
            "venueId", venueId,
            "activityTheme", "教师教学教研借用",
            "activityType", "ACADEMIC",
            "expectedParticipants", 15,
            "startsAt", start.toString(),
            "endsAt", end.toString(),
            "contactPhone", "13911112222"
        )));
        long resId = submitted.path("id").asLong();
        ok(teacher.post("/api/reservations/" + resId + "/withdraw", Map.of()));
        assertThat(ok(affairs.get("/api/notices")).path("items").toString()).contains("/student-affairs/reservations?id=" + resId + "&tab=details");

        // Removing venue_manager assignment clears targetPath on reservation notice
        long affairsId = jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single();
        jdbc.sql("DELETE FROM venue_manager WHERE venue_id=:v AND user_id=:u").param("v", venueId).param("u", affairsId).update();
        try {
            var notices = ok(affairs.get("/api/notices")).path("items");
            for (var item : notices) {
                if (item.path("body").asText().contains("#" + resId + " ")) {
                    assertThat(item.path("targetPath").isNull()).isTrue();
                }
            }
        } finally {
            jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v", venueId).param("u", affairsId).update();
        }

        // Teacher can start a RESERVATION mission without portal:read
        long sessionId = ok(teacher.post("/api/assistant/sessions", Map.of("requestKey", key()))).path("id").asLong();
        var mission = ok(teacher.post("/api/assistant/sessions/" + sessionId + "/missions", Map.of(
            "requestKey", key(),
            "goal", "预约明天下午两点的研讨室",
            "kind", "RESERVATION",
            "mode", "LOCAL",
            "externalConsent", false,
            "fields", Map.of("campus", "EAST", "participants", 10, "startsAt", LocalDate.now().plusDays(12) + "T14:00", "endsAt", LocalDate.now().plusDays(12) + "T16:00", "keyword", "研讨")
        )));
        assertThat(mission.path("kind").asText()).isEqualTo("RESERVATION");
    }

    private static String key() {
        return "key-" + UUID.randomUUID().toString().replace("-", "");
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return json.readTree(response.body());
    }

    private Browser login(String username) throws Exception {
        var b = new Browser();
        b.csrf();
        ok(b.post("/api/auth/login", Map.of("username", username, "password", "Demo@2026")));
        b.csrf();
        return b;
    }

    class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String header, token;

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            var data = json.readTree(get("/api/auth/csrf").body());
            header = data.path("headerName").asText();
            token = data.path("token").asText();
        }

        HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
            return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header(header, token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
        }
    }
}
