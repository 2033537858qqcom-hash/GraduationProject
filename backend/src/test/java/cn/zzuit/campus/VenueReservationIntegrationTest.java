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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:venue-reservation-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo.enabled=true",
        "app.agent.deepseek.enabled=false"
    }
)
class VenueReservationIntegrationTest {

    @LocalServerPort
    int port;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void venuesCatalog_and_conflictCheck() throws Exception {
        var student = login("20260001");

        // 1. 查询全部场地
        var allVenues = ok(student.get("/api/reservations/venues"));
        assertThat(allVenues.isArray()).isTrue();
        assertThat(allVenues.size()).isGreaterThanOrEqualTo(15);

        // 2. 按 3D 场景建筑 placeId 筛选
        var libVenues = ok(student.get("/api/reservations/venues?placeId=east-library"));
        assertThat(libVenues.size()).isGreaterThanOrEqualTo(3);
        for (var v : libVenues) {
            assertThat(v.path("placeId").asText()).isEqualTo("east-library");
        }

        // 3. 按设备类型筛选
        var eqpVenues = ok(student.get("/api/reservations/venues?category=EQUIPMENT"));
        assertThat(eqpVenues.size()).isGreaterThanOrEqualTo(2);

        // 4. 时段冲突检测（空闲时段）
        long venueId = libVenues.get(0).path("id").asLong();
        var start = LocalDateTime.now().plusDays(10).withHour(10).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);

        var conflictCheck = ok(student.send("POST", "/api/reservations/check-conflict", Map.of(
            "venueId", venueId,
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        assertThat(conflictCheck.path("hasConflict").asBoolean()).isFalse();
    }

    @Test
    void reservationFullLifecycle_workflow_and_affairsTimeline() throws Exception {
        var student1 = login("20260001");
        var student2 = login("20260002");
        var reviewer = login("affairs1001");

        // 获取场地
        var gymVenues = ok(student1.get("/api/reservations/venues?placeId=east-gym"));
        long venueId = gymVenues.get(0).path("id").asLong();

        var start = LocalDateTime.now().plusDays(7).withHour(15).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);
        String key = "REQ-" + UUID.randomUUID();

        // 1. 保存草稿
        var draft = ok(student1.send("POST", "/api/reservations/draft", Map.of(
            "requestKey", key,
            "venueId", venueId,
            "activityTheme", "软件学院篮球联赛四强战",
            "activityType", "SPORTS",
            "expectedParticipants", 40,
            "borrowEquipment", "比赛计时计分器",
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        long resId = draft.path("id").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");

        // 2. 提交申请
        var submitted = ok(student1.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", key,
            "venueId", venueId,
            "activityTheme", "软件学院篮球联赛四强战",
            "activityType", "SPORTS",
            "expectedParticipants", 40,
            "borrowEquipment", "比赛计时计分器",
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");

        // 3. 冲突拦截：另一个学生尝试预约重叠时段 -> 409 Conflict
        var conflictResponse = student2.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", "REQ-" + UUID.randomUUID(),
            "venueId", venueId,
            "activityTheme", "冲突测试活动",
            "activityType", "SPORTS",
            "expectedParticipants", 20,
            "contactPhone", "13900139000",
            "startsAt", start.plusMinutes(30).toString(),
            "endsAt", end.plusMinutes(30).toString()
        ));
        assertThat(conflictResponse.statusCode()).isEqualTo(409);

        // 4. 审核人员退回补正
        var returned = ok(reviewer.send("POST", "/api/reservations/" + resId + "/review", Map.of(
            "action", "RETURN",
            "comment", "请明确现场安保负责人及观众席卫生维护方案"
        )));
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");

        // 5. 申请人补交并重新提交
        var resubmitted = ok(student1.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", key,
            "venueId", venueId,
            "activityTheme", "软件学院篮球联赛四强战（已指定班级骨干维护秩序）",
            "activityType", "SPORTS",
            "expectedParticipants", 45,
            "borrowEquipment", "比赛计时计分器",
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        assertThat(resubmitted.path("status").asText()).isEqualTo("SUBMITTED");

        // 6. 审核人员审批通过
        var approved = ok(reviewer.send("POST", "/api/reservations/" + resId + "/review", Map.of(
            "action", "APPROVE",
            "comment", "方案完备，准予借用"
        )));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

        // 7. 验证“我的办事”集成 (/api/affairs/mine)
        var affairsMine = ok(student1.get("/api/affairs/mine?kind=RESERVATION"));
        assertThat(affairsMine.path("items").size()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (var item : affairsMine.path("items")) {
            if (item.path("id").asLong() == resId) {
                found = true;
                assertThat(item.path("name").asText()).isEqualTo("场地借用预约");
                assertThat(item.path("status").asText()).isEqualTo("APPROVED");
                assertThat(item.path("targetPath").asText()).contains("/portal/reservations");
            }
        }
        assertThat(found).isTrue();

        // 8. 验证统一办事时间线 (/api/affairs/mine/RESERVATION/{id}/timeline)
        var timeline = ok(student1.get("/api/affairs/mine/RESERVATION/" + resId + "/timeline"));
        assertThat(timeline.path("requestId").asLong()).isEqualTo(resId);
        assertThat(timeline.path("status").asText()).isEqualTo("APPROVED");
        var events = timeline.path("items");
        assertThat(events.size()).isGreaterThanOrEqualTo(4);
        assertThat(events.get(0).path("action").asText()).isEqualTo("APPROVE");
        assertThat(events.get(0).path("title").asText()).isEqualTo("审批通过借用");

        // 9. 申请人取消预约 -> 状态 CANCELLED
        var cancelled = ok(student1.send("POST", "/api/reservations/" + resId + "/cancel", Map.of(
            "reason", "赛事时间改期，申请释放场地"
        )));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");

        // 10. 取消后时段释放，student2 此时可以顺利预约同一时段
        var student2Submit = ok(student2.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", "REQ-" + UUID.randomUUID(),
            "venueId", venueId,
            "activityTheme", "计算机学院研究生篮球对抗赛",
            "activityType", "SPORTS",
            "expectedParticipants", 20,
            "contactPhone", "13900139000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        assertThat(student2Submit.path("status").asText()).isEqualTo("SUBMITTED");
    }

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
