package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 预约目录与排期接口只对具备预约业务权限的账号开放。
 * 复查发现 /api/reservations/** 仅要求登录，任意登录账号可枚举资源并读取他人已批准预约主题与时段；
 * 该测试锁定修复后的 URL 级授权边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:reservation-access;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true", "app.agent.deepseek.enabled=false"})
class ReservationAccessIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void accountsWithoutReservationPermissionCannotEnumerateVenuesOrReadSchedule() throws Exception {
        long venue = createVenue();
        var academic = login("academic1001");
        assertThat(academic.get("/api/reservations/venues").statusCode()).as("资源目录").isEqualTo(403);
        assertThat(academic.get("/api/reservations/venues/" + venue).statusCode()).as("资源详情").isEqualTo(403);
        assertThat(academic.get("/api/reservations/venues/" + venue + "/schedule?date=2029-04-02").statusCode())
            .as("场地排期").isEqualTo(403);
        var conflict = academic.send("POST", "/api/reservations/check-conflict", Map.of(
            "venueId", venue, "startsAt", "2029-04-02T09:00:00", "endsAt", "2029-04-02T10:00:00"));
        assertThat(conflict.statusCode()).as("冲突查询").isEqualTo(403);

        var admin = login("admin");
        assertThat(admin.get("/api/reservations/venues").statusCode()).as("系统管理员不应默认拥有预约业务权限").isEqualTo(403);
        assertThat(admin.get("/api/reservations/venues/" + venue + "/schedule?date=2029-04-02").statusCode()).isEqualTo(403);
    }

    @Test
    void applicantsAndReviewersKeepTheirReservationReadingAccess() throws Exception {
        long venue = createVenue();
        var student = login("20260001");
        assertThat(student.get("/api/reservations/venues").statusCode()).isEqualTo(200);
        assertThat(student.get("/api/reservations/venues/" + venue).statusCode()).isEqualTo(200);
        assertThat(student.get("/api/reservations/venues/" + venue + "/schedule?date=2029-04-02").statusCode()).isEqualTo(200);
        var conflict = student.send("POST", "/api/reservations/check-conflict", Map.of(
            "venueId", venue, "startsAt", "2029-04-02T09:00:00", "endsAt", "2029-04-02T10:00:00"));
        assertThat(conflict.statusCode()).isEqualTo(200);

        var reviewer = login("affairs1001");
        assertThat(reviewer.get("/api/reservations/venues").statusCode()).isEqualTo(200);
        assertThat(reviewer.get("/api/reservations/venues/" + venue + "/schedule?date=2029-04-02").statusCode()).isEqualTo(200);
    }

    @Test
    void forbiddenRequestsAreAuditedWithDeniedStatus() throws Exception {
        var academic = login("academic1001");
        var logger = (Logger) LoggerFactory.getLogger("cn.zzuit.campus.audit.OperationAudit");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(academic.get("/api/reservations/venues").statusCode()).isEqualTo(403);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).anyMatch(event ->
            event.getFormattedMessage().contains("/api/reservations/venues") && event.getFormattedMessage().contains("Status: 403"));
    }

    private long createVenue() throws Exception {
        var form = new HashMap<String, Object>();
        form.put("campusId", 1);
        form.put("code", "ACCESS-" + UUID.randomUUID().toString().substring(0, 8));
        form.put("name", "鉴权验收场地");
        form.put("category", "VENUE");
        form.put("placeId", "east-library");
        form.put("buildingName", "测试教学楼");
        form.put("locationDetail", "101室");
        form.put("capacity", 30);
        form.put("enabled", true);
        form.put("managerUserIds", List.of(jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single()));
        var response = login("affairs1001").send("POST", "/api/reservations/venues", form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("id").asLong();
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private Browser login(String name) throws Exception { return login(name, "Demo@2026"); }

    private Browser login(String name, String password) throws Exception {
        var browser = new Browser();
        browser.csrf();
        ok(browser.send("POST", "/api/auth/login", Map.of("username", name, "password", password)));
        browser.csrf();
        return browser;
    }

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
