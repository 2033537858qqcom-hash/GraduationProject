package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
      "spring.datasource.url=jdbc:h2:mem:agent-stream;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "app.demo.enabled=true",
      "app.payment.enabled=false",
      "app.payment.followup.enabled=false",
      "app.agent.deepseek.enabled=false",
      "app.cos.enabled=false",
      "app.repair.reminders.enabled=false",
    })
class AgentSessionStreamTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  @LocalServerPort int port;

  @Test
  void streamQueryEmitsProgressStepsAndFinalResult() throws Exception {
    var student = login("20260001");
    long session =
        ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString())))
            .path("id")
            .asLong();

    var streamResponse =
        student.post(
            "/api/assistant/sessions/" + session + "/query/stream",
            Map.of(
                "requestKey",
                UUID.randomUUID().toString(),
                "input",
                Map.of("message", "我的已发布成绩", "mode", "LOCAL", "externalConsent", false)));

    assertThat(streamResponse.statusCode()).isEqualTo(200);
    assertThat(streamResponse.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
    assertThat(streamResponse.body())
        .contains("event:step")
        .contains("正在分析提问与匹配本地规则")
        .contains("正在查询：课程成绩")
        .contains("event:result")
        .contains("\"summary\"")
        .contains("my_grades");
  }

  @Test
  void streamQueryReportsValidationErrorsAsSseErrorEvents() throws Exception {
    var student = login("20260001");
    long session =
        ok(student.post("/api/assistant/sessions", Map.of("requestKey", UUID.randomUUID().toString())))
            .path("id")
            .asLong();

    var streamResponse =
        student.post(
            "/api/assistant/sessions/" + session + "/query/stream",
            Map.of(
                "requestKey",
                UUID.randomUUID().toString(),
                "input",
                Map.of("message", "查课表", "mode", "DEEPSEEK", "externalConsent", false)));

    assertThat(streamResponse.statusCode()).isEqualTo(200);
    assertThat(streamResponse.body())
        .contains("event:error")
        .contains("MODEL_CONSENT_REQUIRED");
  }

  @Test
  void unauthenticatedStreamRequestIsRejected() throws Exception {
    var anonymous = new Browser();
    anonymous.csrf();
    var response =
        anonymous.post(
            "/api/assistant/sessions/1/query/stream",
            Map.of(
                "requestKey",
                UUID.randomUUID().toString(),
                "input",
                Map.of("message", "查课表", "mode", "LOCAL", "externalConsent", false)));
    assertThat(response.statusCode()).isIn(401, 403);
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
