package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
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
        "spring.datasource.url=jdbc:h2:mem:workbench-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo.enabled=true",
        "app.agent.deepseek.enabled=false"
    }
)
class WorkbenchIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void multiWorkspaceResolution_and_taskAggregationOverview() throws Exception {
        // 1. 辅导员账号 (counselor1001)
        var counselor = login("counselor1001");
        var counselorOverview = ok(counselor.get("/api/workbench/overview"));

        assertThat(counselorOverview.path("workspaces").isArray()).isTrue();
        boolean hasCounselorWorkspace = false;
        for (var ws : counselorOverview.path("workspaces")) {
            if ("COUNSELOR".equals(ws.path("code").asText())) {
                hasCounselorWorkspace = true;
                assertThat(ws.path("name").asText()).isEqualTo("班级事务");
                assertThat(ws.path("roleTitle").asText()).isEqualTo("班级辅导员");
            }
        }
        assertThat(hasCounselorWorkspace).isTrue();

        // 2. 多岗位账号：为辅导员兼赋任课教师 (TEACHER) 角色，验证显式工作空间叠加 (R03)
        long counselorId = jdbc.sql("SELECT id FROM app_user WHERE username = 'counselor1001'").query(Long.class).single();
        if (jdbc.sql("SELECT COUNT(*) FROM user_role WHERE user_id = :uid AND role_code = 'TEACHER'").param("uid", counselorId).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO user_role (user_id, role_code) VALUES (:uid, 'TEACHER')").param("uid", counselorId).update();
        }

        var multiOverview = ok(counselor.get("/api/workbench/overview"));
        boolean foundCounselor = false;
        boolean foundTeaching = false;
        for (var ws : multiOverview.path("workspaces")) {
            if ("COUNSELOR".equals(ws.path("code").asText())) foundCounselor = true;
            if ("TEACHING".equals(ws.path("code").asText())) foundTeaching = true;
        }
        assertThat(foundCounselor).as("兼岗人员必须保留班级事务工作空间").isTrue();
        assertThat(foundTeaching).as("兼岗人员必须同时具备教学工作空间").isTrue();

        // 3. 验证统一任务聚合接口 (/api/workbench/tasks)
        var allTasks = ok(counselor.get("/api/workbench/tasks"));
        assertThat(allTasks.has("totalPending")).isTrue();
        assertThat(allTasks.has("domainCounts")).isTrue();
        assertThat(allTasks.has("tasks")).isTrue();

        // 4. 验证按 domain 过滤
        var leaveTasks = ok(counselor.get("/api/workbench/tasks?domain=leave"));
        assertThat(leaveTasks.path("tasks").isArray()).isTrue();
        for (var t : leaveTasks.path("tasks")) {
            assertThat(t.path("domain").asText()).isEqualTo("LEAVE");
        }
    }

    @Test
    void studentAffairs_taskAggregation_and_agentReviewPending() throws Exception {
        var affairs = login("affairs1001");

        // 1. 验证学生事务工作台与工作空间
        var overview = ok(affairs.get("/api/workbench/overview"));
        boolean hasAffairs = false;
        for (var ws : overview.path("workspaces")) {
            if ("AFFAIRS".equals(ws.path("code").asText())) {
                hasAffairs = true;
                assertThat(ws.path("roleTitle").asText()).isEqualTo("学生事务专员");
            }
        }
        assertThat(hasAffairs).isTrue();

        // 2. 验证任务统计与待办列表集成
        var tasksRes = ok(affairs.get("/api/workbench/tasks"));
        assertThat(tasksRes.path("domainCounts").isObject()).isTrue();

        // 3. 验证 AI 助手具备岗位待办工具 my_review_pending (R04)
        var caps = ok(affairs.get("/api/assistant/capabilities"));
        assertThat(caps.path("tools").isArray()).isTrue();
        boolean hasReviewTool = false;
        for (var item : caps.path("tools")) {
            if ("my_review_pending".equals(item.path("name").asText())) {
                hasReviewTool = true;
                break;
            }
        }
        assertThat(hasReviewTool).as("具有审批角色的账号应自动获得 my_review_pending 待办工具").isTrue();
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
