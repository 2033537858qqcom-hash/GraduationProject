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
        "spring.datasource.url=jdbc:h2:mem:venue-reservation-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo.enabled=true",
        "app.agent.deepseek.enabled=false"
    }
)
class VenueReservationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private JdbcClient jdbc;

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

    @Test
    void auth01_adminCannotViewOrCancelOthersReservation() throws Exception {
        var student1 = login("20260001");
        var admin = login("admin");

        var gymVenues = ok(student1.get("/api/reservations/venues?placeId=east-gym"));
        long venueId = gymVenues.get(0).path("id").asLong();

        var start = LocalDateTime.now().plusDays(20).withHour(9).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);
        String key = "REQ-AUTH01-" + UUID.randomUUID();

        var submitted = ok(student1.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", key,
            "venueId", venueId,
            "activityTheme", "AUTH01-测试管理员越权读取与取消",
            "activityType", "ACADEMIC",
            "expectedParticipants", 10,
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        long resId = submitted.path("id").asLong();

        // 1. 管理员尝试读取他人预约详情 -> 403 Forbidden
        var detailResp = admin.get("/api/reservations/" + resId);
        assertThat(detailResp.statusCode()).isEqualTo(403);

        // 2. 管理员尝试取消他人预约 -> 403 Forbidden
        var cancelResp = admin.send("POST", "/api/reservations/" + resId + "/cancel", Map.of(
            "reason", "管理员违规越权取消"
        ));
        assertThat(cancelResp.statusCode()).isEqualTo(403);
    }

    @Test
    void auth02_reviewerCannotReviewOutsideManagedVenues() throws Exception {
        var student1 = login("20260001");
        var affairs = login("affairs1001");

        // 创建新场地并仅由 academic1001 管辖
        jdbc.sql("""
            INSERT INTO venue_resource (campus_id, code, name, category, place_id, building_name, location_detail, capacity, available_equipment, rules, enabled)
            VALUES (1, 'VEN-TEST-AUTH02', '跨管辖隔离场地', 'VENUE', 'isolated-hall', '综合楼', '综合楼301', 30, '投影仪', '规则', TRUE)
        """).update();
        long isolatedVenueId = jdbc.sql("SELECT id FROM venue_resource WHERE code = 'VEN-TEST-AUTH02'").query(Long.class).single();

        long academicId = jdbc.sql("SELECT id FROM app_user WHERE username = 'academic1001'").query(Long.class).single();
        if (jdbc.sql("SELECT COUNT(*) FROM user_role WHERE user_id = :uid AND role_code = 'STUDENT_AFFAIRS'").param("uid", academicId).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO user_role (user_id, role_code) VALUES (:uid, 'STUDENT_AFFAIRS')").param("uid", academicId).update();
        }
        jdbc.sql("INSERT INTO venue_manager (venue_id, user_id) VALUES (:vid, :uid)").param("vid", isolatedVenueId).param("uid", academicId).update();

        var start = LocalDateTime.now().plusDays(22).withHour(9).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);
        String key = "REQ-AUTH02-" + UUID.randomUUID();

        var submitted = ok(student1.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", key,
            "venueId", isolatedVenueId,
            "activityTheme", "AUTH02-跨管辖审核拦截测试",
            "activityType", "ACADEMIC",
            "expectedParticipants", 10,
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        long resId = submitted.path("id").asLong();

        // affairs1001 不在该场地的 venue_manager 中
        // 1. review 列表中不应出现该记录
        var affairsReviewList = ok(affairs.get("/api/reservations/review"));
        for (var item : affairsReviewList.path("items")) {
            assertThat(item.path("id").asLong()).isNotEqualTo(resId);
        }

        // 2. affairs1001 尝试审核该记录 -> 403 Forbidden
        var reviewResp = affairs.send("POST", "/api/reservations/" + resId + "/review", Map.of(
            "action", "APPROVE",
            "comment", "尝试越权审核非管辖场地"
        ));
        assertThat(reviewResp.statusCode()).isEqualTo(403);

        // 3. academic1001 是该场地的 venue_manager，可以正常查看并审批
        var academic = login("academic1001");
        var academicReviewList = ok(academic.get("/api/reservations/review"));
        boolean found = false;
        for (var item : academicReviewList.path("items")) {
            if (item.path("id").asLong() == resId) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();

        var academicApprove = ok(academic.send("POST", "/api/reservations/" + resId + "/review", Map.of(
            "action", "APPROVE",
            "comment", "管辖负责人正常审批通过"
        )));
        assertThat(academicApprove.path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void auth03_prohibitSelfReview() throws Exception {
        // affairs1001 既有审核权限，又兼任教师具有申请权限
        long affairsId = jdbc.sql("SELECT id FROM app_user WHERE username = 'affairs1001'").query(Long.class).single();
        if (jdbc.sql("SELECT COUNT(*) FROM user_role WHERE user_id = :uid AND role_code = 'TEACHER'").param("uid", affairsId).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO user_role (user_id, role_code) VALUES (:uid, 'TEACHER')").param("uid", affairsId).update();
        }

        var affairs = login("affairs1001");
        var gymVenues = ok(affairs.get("/api/reservations/venues?placeId=east-gym"));
        long venueId = gymVenues.get(0).path("id").asLong();

        var start = LocalDateTime.now().plusDays(25).withHour(14).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);
        String key = "REQ-AUTH03-" + UUID.randomUUID();

        var submitted = ok(affairs.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", key,
            "venueId", venueId,
            "activityTheme", "AUTH03-兼岗人员自提自审测试",
            "activityType", "SPORTS",
            "expectedParticipants", 10,
            "contactPhone", "13800138001",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        long resId = submitted.path("id").asLong();

        // 尝试自己审批自己 -> 403 Forbidden 包含 CANNOT_SELF_REVIEW
        var selfReviewResp = affairs.send("POST", "/api/reservations/" + resId + "/review", Map.of(
            "action", "APPROVE",
            "comment", "自己审批自己"
        ));
        assertThat(selfReviewResp.statusCode()).isEqualTo(403);
        assertThat(selfReviewResp.body()).contains("CANNOT_SELF_REVIEW");
    }

    @Test
    void auth04_draftNotVisibleToNonApplicant() throws Exception {
        var student1 = login("20260001");
        var student2 = login("20260002");
        var affairs = login("affairs1001");

        var gymVenues = ok(student1.get("/api/reservations/venues?placeId=east-gym"));
        long venueId = gymVenues.get(0).path("id").asLong();

        var start = LocalDateTime.now().plusDays(28).withHour(10).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);
        String key = "REQ-AUTH04-" + UUID.randomUUID();

        var draft = ok(student1.send("POST", "/api/reservations/draft", Map.of(
            "requestKey", key,
            "venueId", venueId,
            "activityTheme", "AUTH04-草稿隔离隐私保护测试",
            "activityType", "ACADEMIC",
            "expectedParticipants", 5,
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        long draftId = draft.path("id").asLong();

        // 1. 其他学生尝试读取 -> 403 Forbidden
        var respStudent2 = student2.get("/api/reservations/" + draftId);
        assertThat(respStudent2.statusCode()).isEqualTo(403);

        // 2. 场地审核人尝试读取未提交的草稿 -> 403 Forbidden
        var respAffairs = affairs.get("/api/reservations/" + draftId);
        assertThat(respAffairs.statusCode()).isEqualTo(403);

        // 3. 申请人本人可正常读取草稿
        var respOwner = ok(student1.get("/api/reservations/" + draftId));
        assertThat(respOwner.path("id").asLong()).isEqualTo(draftId);
        assertThat(respOwner.path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void task01_rejectSubmissionWhenNoVenueReviewerConfigured() throws Exception {
        var student1 = login("20260001");

        // 创建没有任何 venue_manager 绑定的孤儿场地
        jdbc.sql("""
            INSERT INTO venue_resource (campus_id, code, name, category, place_id, building_name, location_detail, capacity, available_equipment, rules, enabled)
            VALUES (1, 'VEN-ORPHAN-001', '未指定负责人的测试场地', 'VENUE', 'orphan-hall', '综合楼', '综合楼401', 20, '无', '无', TRUE)
        """).update();
        long orphanVenueId = jdbc.sql("SELECT id FROM venue_resource WHERE code = 'VEN-ORPHAN-001'").query(Long.class).single();

        var start = LocalDateTime.now().plusDays(30).withHour(10).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusHours(2);
        String key = "REQ-TASK01-" + UUID.randomUUID();

        // 1. 保存草稿能够正常成功（保留用户填写内容）
        var draft = ok(student1.send("POST", "/api/reservations/draft", Map.of(
            "requestKey", key,
            "venueId", orphanVenueId,
            "activityTheme", "TASK01-无审批人配置阻断测试",
            "activityType", "ACADEMIC",
            "expectedParticipants", 8,
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        )));
        long draftId = draft.path("id").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");

        // 2. 提交申请时阻断，返回 409 Conflict 且明确提示 NO_VENUE_REVIEWER
        var submitResp = student1.send("POST", "/api/reservations/submit", Map.of(
            "requestKey", key,
            "venueId", orphanVenueId,
            "activityTheme", "TASK01-无审批人配置阻断测试",
            "activityType", "ACADEMIC",
            "expectedParticipants", 8,
            "contactPhone", "13800138000",
            "startsAt", start.toString(),
            "endsAt", end.toString()
        ));
        assertThat(submitResp.statusCode()).isEqualTo(409);
        assertThat(submitResp.body()).contains("NO_VENUE_REVIEWER");

        // 3. 校验草稿数据完好无损，用户不需要重新录入
        var preserved = ok(student1.get("/api/reservations/" + draftId));
        assertThat(preserved.path("status").asText()).isEqualTo("DRAFT");
        assertThat(preserved.path("activityTheme").asText()).isEqualTo("TASK01-无审批人配置阻断测试");
    }

    @Test
    void r05_venueManagementPermissionsAndManagerBinding() throws Exception {
        var admin = login("admin");
        var affairs = login("affairs1001");
        var student = login("20260001");

        // 1. 普通学生调用管理接口 -> 403
        assertThat(student.get("/api/reservations/venues/manage").statusCode()).isEqualTo(403);
        assertThat(student.get("/api/reservations/reviewers/candidates").statusCode()).isEqualTo(403);

        // 2. 系统管理员没有 reservation:manage 权限 -> 403 (权限解耦)
        assertThat(admin.get("/api/reservations/venues/manage").statusCode()).isEqualTo(403);

        // 3. 学生事务 (STUDENT_AFFAIRS) 持有 reservation:manage 权限 -> 200
        var manageList = ok(affairs.get("/api/reservations/venues/manage"));
        assertThat(manageList.isArray()).isTrue();
        assertThat(manageList.size()).isGreaterThanOrEqualTo(1);

        // 4. 获取可选审批候选人 (具备 reservation:review 权限的用户)
        var candidates = ok(affairs.get("/api/reservations/reviewers/candidates"));
        assertThat(candidates.isArray()).isTrue();
        assertThat(candidates.size()).isGreaterThanOrEqualTo(1);
        long candidateId = candidates.get(0).path("userId").asLong();

        // 5. 为首个场地配置管理责任人
        long venueId = manageList.get(0).path("id").asLong();
        var addResp = ok(affairs.send("POST", "/api/reservations/venues/" + venueId + "/managers", Map.of("userId", candidateId)));
        assertThat(addResp.path("success").asBoolean()).isTrue();

        // 6. 验证已被加入
        var updatedList = ok(affairs.get("/api/reservations/venues/manage"));
        var venueItem = java.util.stream.StreamSupport.stream(updatedList.spliterator(), false)
            .filter(node -> node.path("id").asLong() == venueId)
            .findFirst().orElseThrow();
        var managerIds = java.util.stream.StreamSupport.stream(venueItem.path("managers").spliterator(), false)
            .map(m -> m.path("userId").asLong())
            .toList();
        assertThat(managerIds).contains(candidateId);

        // 7. 移除管理责任人
        var removeResp = ok(affairs.send("DELETE", "/api/reservations/venues/" + venueId + "/managers/" + candidateId, Map.of()));
        assertThat(removeResp.path("success").asBoolean()).isTrue();

        // 8. 尝试将没有 reservation:review 权限的学生指定为审批责任人 -> 400 USER_NOT_REVIEWER
        long studentId = jdbc.sql("SELECT id FROM app_user WHERE username = '20260001'").query(Long.class).single();
        var invalidAdd = affairs.send("POST", "/api/reservations/venues/" + venueId + "/managers", Map.of("userId", studentId));
        assertThat(invalidAdd.statusCode()).isEqualTo(400);
        assertThat(invalidAdd.body()).contains("USER_NOT_REVIEWER");
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
