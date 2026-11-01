package cn.zzuit.campus.payment;
import static org.assertj.core.api.Assertions.assertThat;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:batch-preview;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false",
 "app.cos.enabled=false","app.agent.deepseek.enabled=false","app.repair.reminders.enabled=false"})
class BatchBillPreviewHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 private Map<String,Object> input(List<String> names) {
  return Map.of("requestKey",UUID.randomUUID().toString(),"usernames",names,"template",Map.of(
   "categoryCode","TUITION","title","批量预览测试","billingPeriod","预览验收",
   "dueDate",LocalDate.now().plusDays(7).toString(),"quantity","1","amountCents",1250,
   "basis","依据隔离测试标准进行批量收费核对"));
 }
 @Test void previewReportsEachStudentAndTotalsWithoutWritingBills() throws Exception {
  var op=login("finance1001");long before=jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single();
  var result=ok(op.post("/api/finance/payments/batch/preview",input(List.of("20260001","missing-student","20260002"))));
  assertThat(result.path("items").size()).isEqualTo(3);
  assertThat(result.path("validCount").asInt()).isEqualTo(2);
  assertThat(result.path("totalAmountCents").asLong()).isEqualTo(2500);
  assertThat(result.path("canCreate").asBoolean()).isFalse();
  assertThat(result.path("items").get(0).path("studentName").asText()).isNotBlank();
  assertThat(result.path("items").get(1).path("errorCode").asText()).isEqualTo("STUDENT_NOT_FOUND");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single()).isEqualTo(before);
 }
 @Test void previewAndCreationUseSamePricingAndRejectInvalidBatches() throws Exception {
  var op=login("finance1001");var body=input(List.of("20260001","20260002"));
  var preview=ok(op.post("/api/finance/payments/batch/preview",body));
  assertThat(preview.path("canCreate").asBoolean()).isTrue();
  var bills=ok(op.post("/api/finance/payments/batch",body));
  assertThat(bills.get(0).path("amountCents").asLong()+bills.get(1).path("amountCents").asLong()).isEqualTo(preview.path("totalAmountCents").asLong());
  assertThat(op.post("/api/finance/payments/batch/preview",input(List.of("20260001","20260001"))).statusCode()).isEqualTo(400);
  assertThat(op.post("/api/finance/payments/batch/preview",input(Collections.nCopies(101,"20260001"))).statusCode()).isEqualTo(400);
  assertThat(login("finance2001").post("/api/finance/payments/batch/preview",body).statusCode()).isEqualTo(403);
  assertThat(login("20260001").post("/api/finance/payments/batch/preview",body).statusCode()).isEqualTo(403);
 }
    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return JSON.readTree(response.body());
    }
    private Browser login(String username) throws Exception {
        var browser = new Browser(); browser.csrf();
        ok(browser.post("/api/auth/login", Map.of("username", username, "password", "Demo@2026"))); browser.csrf(); return browser;
    }
    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); String header, token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception { var data = JSON.readTree(get("/api/auth/csrf").body()); header = data.path("headerName").asText(); token = data.path("token").asText(); }
        HttpResponse<String> post(String path, Map<String, ?> body) throws Exception { return send("POST", path, body); }
        HttpResponse<String> send(String method, String path, Map<String, ?> body) throws Exception {
            return http.send(builder(path).header(header, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
