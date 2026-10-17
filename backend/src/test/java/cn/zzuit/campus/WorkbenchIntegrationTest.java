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

    @Test
    void categoryQueryAndPaginationReachTasksOutsideTheEightItemOverview() throws Exception {
        String name="queue_"+java.util.UUID.randomUUID().toString().substring(0,8);
        jdbc.sql("""
            INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo)
            SELECT :name,password_hash,'分页测试审批人',:name,campus_id,department_id,TRUE FROM app_user WHERE username='affairs1001'
            """).param("name",name).update();
        long reviewerId=jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'STUDENT_AFFAIRS')").param("id",reviewerId).update();
        String venueCode="PAGE-"+java.util.UUID.randomUUID();
        jdbc.sql("INSERT INTO venue_resource(campus_id,code,name,location_detail) VALUES(1,:code,'分页测试资源','隔离测试区')").param("code",venueCode).update();
        long venue=jdbc.sql("SELECT id FROM venue_resource WHERE code=:code").param("code",venueCode).query(Long.class).single();
        jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v",venue).param("u",reviewerId).update();
        var start=java.time.LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0);
        for(int i=0;i<107;i++) {
            jdbc.sql("""
                INSERT INTO venue_reservation(applicant_id,request_key,venue_id,applicant_name,identity_number,contact_phone,
                    department_name,class_name,venue_name,location_detail,activity_theme,activity_type,expected_participants,
                    starts_at,ends_at,status)
                SELECT id,:key,:venue,full_name,identity_number,'13800138001','信息工程学院','演示班',
                    '分页测试资源','隔离测试区',:theme,'ACADEMIC',10,:start,:end,'SUBMITTED'
                FROM app_user WHERE username='20260001'
                """).param("key",java.util.UUID.randomUUID().toString()).param("venue",venue)
                .param("theme","分页测试活动"+i).param("start",start.plusMinutes(40L*i)).param("end",start.plusMinutes(40L*i+30)).update();
        }
        var student=login("20260001");
        var certificate=ok(student.send("POST","/api/certificates/mine",Map.of("requestKey",java.util.UUID.randomUUID().toString(),
            "purpose","分页之外的证明待办","deliveryMethod","DIGITAL","identityConfirmed",true,"version",0)));
        long certificateId=certificate.path("id").asLong();
        ok(student.send("POST","/api/certificates/mine/"+certificateId+"/submit",Map.of("version",certificate.path("version").asInt(),"comment","提交分页验证")));
        var reviewer=login(name);
        var overview=ok(reviewer.get("/api/workbench/overview"));
        assertThat(overview.path("tasks").size()).isEqualTo(8);
        assertThat(overview.path("taskCounts").path("reservation").asLong()).isEqualTo(107);
        var proof=ok(reviewer.get("/api/workbench/tasks?domain=CERTIFICATE&page=1&pageSize=8"));
        assertThat(proof.path("complete").asBoolean()).isTrue();
        assertThat(proof.path("total").asLong()).isEqualTo(proof.path("domainCounts").path("certificate").asLong());
        assertThat(proof.path("tasks").toString()).contains("分页之外的证明待办");
        var ids=new java.util.HashSet<String>();
        for(int page=1;page<=14;page++) {
            var batch=ok(reviewer.get("/api/workbench/tasks?domain=RESERVATION&page="+page+"&pageSize=8"));
            assertThat(batch.path("total").asLong()).isEqualTo(107);
            assertThat(batch.path("page").asInt()).isEqualTo(page);
            assertThat(batch.path("tasks").size()).isEqualTo(page==14?3:8);
            for(var item:batch.path("tasks")) {
                assertThat(item.path("domain").asText()).isEqualTo("RESERVATION");
                assertThat(ids.add(item.path("id").asText())).isTrue();
            }
        }
        assertThat(ids).hasSize(107);
        assertThat(ok(reviewer.get("/api/workbench/tasks?domain=RESERVATION&page=15&pageSize=8")).path("tasks").size()).isZero();
        assertThat(reviewer.get("/api/workbench/tasks?page=0").statusCode()).isEqualTo(400);
        assertThat(reviewer.get("/api/workbench/tasks?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(reviewer.get("/api/workbench/tasks?domain=UNKNOWN").statusCode()).isEqualTo(400);
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
