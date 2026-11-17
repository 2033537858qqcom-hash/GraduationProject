package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;

import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:agent-optimization;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "app.demo.enabled=true",
      "app.payment.enabled=false",
      "app.payment.followup.enabled=false",
      "app.agent.deepseek.enabled=false",
      "app.cos.enabled=false",
      "app.repair.reminders.enabled=false",
    })
class AgentOptimizationIntegrationTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  @LocalServerPort int port;
  @Autowired AgentGuides guides;
  @MockitoSpyBean DeepSeekClient model;

  @BeforeEach
  void offline() {
    doReturn(false).when(model).ready();
  }

  @Test
  void guideSearchExpandsCampusSynonymsWithoutMatchingUnknownTopics() {
    assertThat(guides.search("我想换宿舍怎么弄")).extracting(AgentModels.Guide::slug).contains("housing");
    assertThat(guides.search("宿舍断电了怎么报修")).extracting(AgentModels.Guide::slug).contains("repair");
    assertThat(guides.search("跨境联合培养补贴未知指南")).isEmpty();
  }

  @Test
  void navigationParsesOriginAndDestinationInsteadOfDefaultingToSouthGate() throws Exception {
    var student = login("20260001");
    var answer = ok(student.post("/api/assistant/query", Map.of("message", "从图书馆到红体馆怎么走", "mode", "LOCAL", "externalConsent", false)));
    assertThat(answer.path("results").get(0).path("name").asText()).isEqualTo("campus_navigation");
    assertThat(answer.toString()).contains("图书馆", "红体馆").doesNotContain("从【南门】");
  }

  @Test
  void readOnlyLookupOfCancelledRecordsIsAllowedWhileWriteAttemptsRemainRefused() throws Exception {
    var student = login("20260001");
    var allowed = ok(student.post("/api/assistant/query", Map.of("message", "帮我查下已取消的预约", "mode", "LOCAL", "externalConsent", false)));
    assertThat(allowed.path("results").get(0).path("name").asText()).isEqualTo("my_reservations");
    assertThat(allowed.path("summary").asText()).doesNotContain("不能查询无关人员资料");

    var refused = ok(student.post("/api/assistant/query", Map.of("message", "帮我取消这个预约", "mode", "LOCAL", "externalConsent", false)));
    assertThat(refused.path("results")).isEmpty();
    assertThat(refused.path("summary").asText()).contains("不能");
  }

  @Test
  void generalFollowUpResolvesNextWeekTimetableAndDomainGuideLocally() throws Exception {
    var student = login("20260001");
    long session = ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString()))).path("id").asLong();
    String path = "/api/assistant/sessions/" + session + "/query";

    ok(student.post(path, Map.of("requestKey", UUID.randomUUID().toString(), "input", Map.of("message", "本周课表", "mode", "LOCAL", "externalConsent", false))));
    var nextWeek = ok(student.post(path, Map.of("requestKey", UUID.randomUUID().toString(), "input", Map.of("message", "那下周呢？", "mode", "LOCAL", "externalConsent", false))));
    assertThat(nextWeek.path("answer").path("results").get(0).path("name").asText()).isEqualTo("my_timetable");

    ok(student.post(path, Map.of("requestKey", UUID.randomUUID().toString(), "input", Map.of("message", "我的报修进度", "mode", "LOCAL", "externalConsent", false))));
    var guideFollowUp = ok(student.post(path, Map.of("requestKey", UUID.randomUUID().toString(), "input", Map.of("message", "那办理规则呢？", "mode", "LOCAL", "externalConsent", false))));
    assertThat(guideFollowUp.path("answer").path("results").get(0).path("name").asText()).isEqualTo("search_guides");
    assertThat(guideFollowUp.toString()).contains("repair");
  }

  @Test
  void missionStreamAndMetricsEndpointsWorkWithPermissions() throws Exception {
    assertThat(new Browser().get("/api/assistant/metrics").statusCode()).isEqualTo(401);
    var student = login("20260001");
    long session = ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString()))).path("id").asLong();

    var stream = student.post(
        "/api/assistant/sessions/" + session + "/missions/stream",
        Map.of(
            "requestKey", UUID.randomUUID().toString(),
            "goal", "帮我准备在校证明草稿",
            "kind", "CERTIFICATE",
            "mode", "LOCAL",
            "externalConsent", false));
    assertThat(stream.statusCode()).isEqualTo(200);
    assertThat(stream.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
    assertThat(stream.body()).contains("event:step").contains("event:result");

    ok(student.post("/api/assistant/query", Map.of("message", "我有多少未读消息", "mode", "LOCAL", "externalConsent", false)));
    var metrics = ok(student.get("/api/assistant/metrics"));
    assertThat(metrics.path("totalRuns").asLong()).isPositive();
    assertThat(metrics.path("topTools").toString()).contains("my_notice_summary");
  }

  @Test
  void westCampusNavigationAndCrossCampusGuidanceWork() throws Exception {
    var student = login("20260001");
    var west = ok(student.post("/api/assistant/query", Map.of("message", "从西校区图书馆到思源食府怎么走", "mode", "LOCAL", "externalConsent", false)));
    assertThat(west.path("results").get(0).path("name").asText()).isEqualTo("campus_navigation");
    assertThat(west.toString()).contains("西校区图书馆", "思源餐厅", "/campus/explore?campus=WEST&navigate=west-siyuan-canteen");

    var cross = ok(student.post("/api/assistant/query", Map.of("message", "从图书馆到西校区明德讲堂怎么走", "mode", "LOCAL", "externalConsent", false)));
    assertThat(cross.toString()).contains("跨校区行程", "/campus/explore?campus=WEST&navigate=west-mingde");
  }

  @Test
  void crossToolScheduleAndVenueSynthesisWithDaHouTianWorks() throws Exception {
    var student = login("20260001");
    var answer = ok(student.post("/api/assistant/query", Map.of("message", "看看大后天日程，以及大后天东校区 20人 09:00-11:00 空闲场地", "mode", "LOCAL", "externalConsent", false)));
    assertThat(answer.path("results")).hasSize(2);
    assertThat(java.util.stream.StreamSupport.stream(answer.path("results").spliterator(), false)
        .map(node -> node.path("name").asText()).toList())
        .containsExactlyInAnyOrder("my_schedule", "find_available_venues");
    assertThat(answer.path("summary").asText()).contains("查询");
  }

  @Test
  void housingMissionSavesDraftEndToEnd() throws Exception {
    var student = login("20260002");
    long session = ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString()))).path("id").asLong();
    var created = ok(student.post(
        "/api/assistant/sessions/" + session + "/missions",
        Map.of(
            "requestKey", UUID.randomUUID().toString(),
            "goal", "帮我准备调宿申请草稿",
            "kind", "HOUSING",
            "mode", "LOCAL",
            "externalConsent", false,
            "housingFields", Map.of(
                "reason", "专业跨校区授课通勤不便",
                "preferredArea", "西校区四号宿舍楼",
                "materialNote", "已准备学院教务转专业证明"))));
    assertThat(created.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
    assertThat(created.path("kind").asText()).isEqualTo("HOUSING");
    assertThat(created.path("housingPreview").path("currentPlace").asText()).contains("静逸楼");

    var confirmed = ok(student.post(
        "/api/assistant/sessions/" + session + "/missions/" + created.path("id").asLong() + "/actions",
        Map.of(
            "requestKey", UUID.randomUUID().toString(),
            "version", created.path("version").asInt(),
            "action", "CONFIRM",
            "confirmed", true,
            "proposalHash", created.path("proposalHash").asText())));
    assertThat(confirmed.path("stage").asText()).isEqualTo("DONE");
    assertThat(confirmed.path("business").path("status").asText()).isEqualTo("DRAFT");
    assertThat(confirmed.path("business").path("targetPath").asText()).contains("/housing/transfer?id=");
  }

  @Test
  void feedbackUpsertOwnershipAndAdminOverviewMetricsWork() throws Exception {
    var student = login("20260001");
    var other = login("20260002");
    var admin = login("admin");

    var ans = ok(student.post("/api/assistant/query", Map.of("message", "我有多少未读消息", "mode", "LOCAL", "externalConsent", false)));
    String runId = ans.path("runId").asText();

    assertThat(other.post("/api/assistant/runs/" + runId + "/feedback", Map.of("rating", "UP")).statusCode()).isEqualTo(404);
    assertThat(student.get("/api/assistant/metrics/overview").statusCode()).isEqualTo(403);

    var fb1 = ok(student.post("/api/assistant/runs/" + runId + "/feedback", Map.of("rating", "UP", "comment", "准确")));
    assertThat(fb1.path("rating").asText()).isEqualTo("UP");
    var fb2 = ok(student.post("/api/assistant/runs/" + runId + "/feedback", Map.of("rating", "DOWN", "comment", "需补充")));
    assertThat(fb2.path("rating").asText()).isEqualTo("DOWN");

    var personal = ok(student.get("/api/assistant/metrics"));
    assertThat(personal.path("downFeedbacks").asLong()).isGreaterThanOrEqualTo(1L);

    var overview = ok(admin.get("/api/assistant/metrics/overview"));
    assertThat(overview.path("totalRuns").asLong()).isPositive();
    assertThat(overview.path("activeUsers").asLong()).isGreaterThanOrEqualTo(1L);
    assertThat(overview.path("downFeedbacks").asLong()).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void deepSeekReferentialFollowUpIncludesSanitizedPriorTopicWithoutPii() throws Exception {
    doReturn(true).when(model).ready();
    doReturn(new AgentModels.Plan(List.of(new AgentModels.Call("my_notice_summary", JSON.readTree("{}"))), 5, 5))
        .when(model).plan(any(), anyList(), any());
    var student = login("20260001");
    long session = ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString()))).path("id").asLong();
    String path = "/api/assistant/sessions/" + session + "/query";
    ok(student.post(path, Map.of("requestKey", UUID.randomUUID().toString(), "input", Map.of("message", "查询本周课表 学号20260001 邮箱a@b.com", "mode", "LOCAL", "externalConsent", false))));
    var captured = org.mockito.ArgumentCaptor.forClass(String.class);
    ok(student.post(path, Map.of("requestKey", UUID.randomUUID().toString(), "input", Map.of("message", "那结合刚才的课表查一下未读消息", "mode", "DEEPSEEK", "externalConsent", true))));
    org.mockito.Mockito.verify(model).plan(captured.capture(), anyList(), any());
    assertThat(captured.getValue()).contains("同会话上轮问题主题").contains("[编号]").contains("[邮箱]").doesNotContain("20260001").doesNotContain("a@b.com");
  }

  @Test
  void dijkstraNavigationProvidesWaypointsAndDistanceEstimate() throws Exception {
    var student = login("20260001");
    var east = ok(student.post("/api/assistant/query", Map.of("message", "从南门到图书馆怎么走", "mode", "LOCAL", "externalConsent", false)));
    assertThat(east.toString()).contains("步行推荐路线", "估算距离约", "分钟", "南门", "图书馆");
  }

  @Test
  void semanticTfIdfGuideSearchMatchesOralPhrasingWhileBlockingUnknown() {
    assertThat(guides.search("我想借教室开组会需要什么条件")).extracting(AgentModels.Guide::slug).contains("reservation");
    assertThat(guides.search("跨境联合培养补贴未知指南")).isEmpty();
  }

  @Test
  void deepSeekSummarizeStreamParsesSseDeltas() throws Exception {
    var chunks = new java.util.ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"共找到\"}}]}\n\n"
          + "data: {\"choices\":[{\"delta\":{\"content\":\" 2 个空闲教室。\"}}]}\n\n"
          + "data: [DONE]\n\n";
      byte[] body = sse.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, body.length);
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    try {
      var client = new DeepSeekClient(HttpClient.newHttpClient(), "test-key", "deepseek-flash",
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"), true, 3);
      String summary = client.summarizeStream("找教室", "[{\"tool\":\"find_available_venues\",\"total\":2}]", chunks::add);
      assertThat(summary).isEqualTo("共找到 2 个空闲教室。");
      assertThat(chunks).containsExactly("共找到", " 2 个空闲教室。");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void sessionPersistsSafeTurnSummaryBriefingStaffAdviceAndP95Overview() throws Exception {
    var student = login("20260001");
    var admin = login("admin");
    long session = ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString()))).path("id").asLong();
    ok(student.post("/api/assistant/sessions/" + session + "/query", Map.of(
        "requestKey", UUID.randomUUID().toString(),
        "input", Map.of("message", "我有多少未读消息", "mode", "LOCAL", "externalConsent", false))));
    var restored = ok(student.get("/api/assistant/sessions/" + session));
    assertThat(restored.path("turns").get(0).path("answerSummary").asText()).contains("消息");
    assertThat(restored.toString()).doesNotContain("results", "items", "unreadCount", "externalConsent");

    var briefing = ok(student.get("/api/assistant/briefing"));
    assertThat(briefing.has("unreadNotices")).isTrue();
    assertThat(briefing.path("suggestions").isArray()).isTrue();

    var digest = ok(admin.post("/api/assistant/query", Map.of("message", "生成岗位运行简报", "mode", "LOCAL", "externalConsent", false)));
    assertThat(digest.toString()).contains("platform_digest");

    var overview = ok(admin.get("/api/assistant/metrics/overview"));
    assertThat(overview.has("p95DurationMs")).isTrue();
    assertThat(overview.has("outcomeCounts")).isTrue();
  }

  @Test
  void repairPhotoAnalyzerValidatesPngAndSuggestsCategory() throws Exception {
    var student = login("20260001");
    var img = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var baos = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(img, "png", baos);
    String boundary = "----CampusPhotoTest";
    byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"宿舍漏水.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] multipart = new byte[head.length + baos.size() + tail.length];
    System.arraycopy(head, 0, multipart, 0, head.length);
    System.arraycopy(baos.toByteArray(), 0, multipart, head.length, baos.size());
    System.arraycopy(tail, 0, multipart, head.length + baos.size(), tail.length);

    var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/assistant/repair/analyze-photo"))
        .header(student.header, student.token)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
        .build();
    var res = student.http.send(req, HttpResponse.BodyHandlers.ofString());
    var node = ok(res);
    assertThat(node.path("category").asText()).isEqualTo("WATER");
    assertThat(node.path("resolution").asText()).isEqualTo("40×30");
  }

  @Test
  void deepSeekClientRetriesOnceOnTransient503() throws Exception {
    var attempts = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      int n = attempts.incrementAndGet();
      byte[] body = (n == 1
              ? "{\"error\":\"busy\"}"
              : "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"my_notice_summary\",\"arguments\":\"{}\"}}]}}]}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(n == 1 ? 503 : 200, body.length);
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    try {
      var client = new DeepSeekClient(HttpClient.newHttpClient(), "test-key", "deepseek-flash", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"), true, 3);
      var plan = client.plan("我有多少消息", List.of(), null);
      assertThat(attempts.get()).isEqualTo(2);
      assertThat(plan.calls()).hasSize(1);
      assertThat(plan.calls().getFirst().name()).isEqualTo("my_notice_summary");
    } finally {
      server.stop(0);
    }
  }

  private JsonNode ok(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private Browser login(String username) throws Exception {
    var browser = new Browser();
    browser.csrf();
    ok(browser.post("/api/auth/login", Map.of("username", username, "password", "Demo@2026")));
    browser.csrf();
    return browser;
  }

  private class Browser {
    final HttpClient http =
        HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String header, token;

    HttpRequest.Builder builder(String path) {
      return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    HttpResponse<String> get(String path) throws Exception {
      return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    void csrf() throws Exception {
      var data = JSON.readTree(get("/api/auth/csrf").body());
      header = data.path("headerName").asText();
      token = data.path("token").asText();
    }

    HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
      return http.send(
          builder(path)
              .header(header, token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
