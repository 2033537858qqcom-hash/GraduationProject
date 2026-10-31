package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:finance-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true", "app.payment.enabled=true", "app.payment.channel=remote",
    "app.payment.followup.enabled=false", "app.cos.enabled=false", "app.agent.deepseek.enabled=false",
    "app.repair.reminders.enabled=false"})
class FinanceWorkflowHttpTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<String, String> amounts = new ConcurrentHashMap<>();
    static final Set<String> paid = ConcurrentHashMap.newKeySet();
    static final HttpServer GATEWAY = gateway();
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource static void remote(DynamicPropertyRegistry properties) {
        properties.add("app.payment.remote-url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
        properties.add("app.payment.remote-token", () -> "local-finance-contract-token");
    }
    @AfterAll static void stopGateway() { GATEWAY.stop(0); }

    @Test void systemAdministratorCannotCreateOrReviewFinancialBills() throws Exception {
        var admin = login("admin");
        assertThat(admin.post("/api/admin/payments", legacyFields()).statusCode()).isEqualTo(403);
        assertThat(admin.get("/api/finance/payments").statusCode()).isEqualTo(403);
        assertThat(admin.post("/api/finance/payments", fields()).statusCode()).isEqualTo(403);
        assertThat(login("20260001").get("/api/finance/payments").statusCode()).isEqualTo(403);
        assertThat(new Browser().get("/api/finance/payments").statusCode()).isEqualTo(401);
    }

    @Test void financeRolesHaveSeparateCapabilitiesAndAWorkplace() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001");
        assertThat(ok(operator.get("/api/me")).toString()).contains("payment:manage").doesNotContain("foundation:manage", "payment:review");
        assertThat(ok(reviewer.get("/api/me")).toString()).contains("payment:review").doesNotContain("payment:manage");
        assertThat(ok(operator.get("/api/workbench/overview")).toString()).contains("FINANCE", "/finance/payments");
        assertThat(ok(operator.get("/api/finance/payments/catalog")).path("categories").size()).isEqualTo(8);
        assertThat(reviewer.post("/api/finance/payments", fields()).statusCode()).isEqualTo(403);
    }

    @Test void draftIsHiddenUntilAnotherPersonPublishesItAndNotifiesTheStudent() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001"); var student = login("20260001");
        var draft = ok(operator.post("/api/finance/payments", fields())); long id = draft.path("id").asLong();
        assertThat(draft.path("workflowStatus").asText()).isEqualTo("DRAFT");
        assertThat(ok(student.get("/api/payments/mine")).toString()).doesNotContain(draft.path("orderNo").asText());
        assertThat(student.get("/api/payments/mine/" + id).statusCode()).isEqualTo(404);
        assertThat(student.post("/api/payments/mine/" + id + "/pay", Map.of()).statusCode()).isEqualTo(404);
        var submitted = action(operator, id, "SUBMIT", draft.path("version").asInt());
        assertThat(submitted.path("workflowStatus").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(operator.post("/api/finance/payments/" + id + "/actions", command("APPROVE", submitted.path("version").asInt())).statusCode()).isEqualTo(403);
        assertThat(ok(reviewer.get("/api/workbench/tasks?domain=PAYMENT")).toString()).contains("PAYMENT-" + id);
        var published = action(reviewer, id, "APPROVE", submitted.path("version").asInt());
        assertThat(published.path("workflowStatus").asText()).isEqualTo("PUBLISHED");
        assertThat(ok(student.get("/api/payments/mine/" + id)).path("amountCents").asLong()).isEqualTo(1250);
        var notices = ok(student.get("/api/notices?pageSize=100"));
        assertThat(notices.toString()).contains("/portal/payments?id=" + id + "&tab=details");
        assertThat(ok(operator.get("/api/finance/payments/" + id + "/history")).size()).isGreaterThanOrEqualTo(3);
    }

    @Test void standardPriceAndQuantityDetermineTheAmountOnTheServer() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001");
        var standard = ok(operator.post("/api/finance/payments/standards", Map.of(
            "requestKey", UUID.randomUUID().toString(), "name", "独立演示水费标准", "categoryCode", "WATER",
            "unit", "吨", "unitPriceCents", 320, "reason", "根据本次独立演示配置核定")));
        long standardId = standard.path("id").asLong();
        assertThat(standard.path("status").asText()).isEqualTo("PENDING_REVIEW");
        var input = fields(); input.put("categoryCode", "WATER"); input.put("standardId", standardId); input.put("quantity", "2.5"); input.put("amountCents", 1);
        assertThat(operator.post("/api/finance/payments", input).statusCode()).isEqualTo(409);
        ok(reviewer.post("/api/finance/payments/standards/" + standardId + "/actions", command("APPROVE", 0)));
        var bill = ok(operator.post("/api/finance/payments", input));
        assertThat(bill.path("amountCents").asLong()).isEqualTo(800);
        assertThat(bill.path("standardName").asText()).isEqualTo("独立演示水费标准");
        for (String quantity : new String[]{"0", "-1", "1e3", "NaN", "1.0001", "999999999999"}) {
            input.put("requestKey", UUID.randomUUID().toString()); input.put("quantity", quantity);
            assertThat(operator.post("/api/finance/payments", input).statusCode()).isEqualTo(400);
        }
    }

    @Test void returnedBillsCanBeCorrectedButStaleVersionsCannotOverwriteThem() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001");
        var draft = ok(operator.post("/api/finance/payments", fields())); long id = draft.path("id").asLong();
        var submitted = action(operator, id, "SUBMIT", 0);
        var returned = action(reviewer, id, "RETURN", submitted.path("version").asInt());
        assertThat(returned.path("workflowStatus").asText()).isEqualTo("RETURNED");
        var update = fields(); update.put("version", returned.path("version").asInt()); update.put("title", "修正后的学费账单");
        var corrected = ok(operator.send("PUT", "/api/finance/payments/" + id, update));
        assertThat(corrected.path("title").asText()).isEqualTo("修正后的学费账单");
        assertThat(operator.send("PUT", "/api/finance/payments/" + id, update).statusCode()).isEqualTo(409);
    }

    @Test void actionRetriesAreIdempotentAndChangedCreatePayloadsConflict() throws Exception {
        var operator = login("finance1001"); var input = fields();
        var draft = ok(operator.post("/api/finance/payments", input)); long id = draft.path("id").asLong();
        assertThat(ok(operator.post("/api/finance/payments", input)).path("id").asLong()).isEqualTo(id);
        input.put("billingPeriod", "另一个账期");
        assertThat(operator.post("/api/finance/payments", input).statusCode()).isEqualTo(409);
        var command = command("SUBMIT", 0);
        var first = ok(operator.post("/api/finance/payments/" + id + "/actions", command));
        var again = ok(operator.post("/api/finance/payments/" + id + "/actions", command));
        assertThat(again.path("version").asInt()).isEqualTo(first.path("version").asInt());
    }

    @Test void theSamePersonCannotApproveEvenWhenHoldingBothFinanceRoles() throws Exception {
        long userId = jdbc.sql("SELECT id FROM app_user WHERE username='finance1001'").query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'FINANCE_REVIEWER')").param("id", userId).update();
        try {
            var actor = login("finance1001"); var draft = ok(actor.post("/api/finance/payments", fields()));
            var submitted = action(actor, draft.path("id").asLong(), "SUBMIT", 0);
            assertThat(actor.post("/api/finance/payments/" + draft.path("id").asLong() + "/actions", command("APPROVE", submitted.path("version").asInt())).statusCode()).isEqualTo(403);
        } finally { jdbc.sql("DELETE FROM user_role WHERE user_id=:id AND role_code='FINANCE_REVIEWER'").param("id", userId).update(); }
    }

    @Test void publicationVoidAndWaiverNeedAnIndependentReview() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001");
        for (var requestType : new String[]{"VOID", "WAIVER"}) {
            var bill = publish(operator, reviewer); long id = bill.path("id").asLong();
            var request = ok(operator.post("/api/finance/payments/" + id + "/requests", Map.of(
                "requestKey", UUID.randomUUID().toString(), "type", requestType, "reason", "经核对需调整本次演示账单", "version", bill.path("version").asInt())));
            long requestId = request.path("id").asLong();
            assertThat(login("20260001").post("/api/payments/mine/" + id + "/pay", Map.of()).statusCode()).isEqualTo(409);
            ok(reviewer.post("/api/finance/payments/requests/" + requestId + "/actions", command("APPROVE", 0)));
            assertThat(ok(operator.get("/api/finance/payments/" + id)).path("workflowStatus").asText()).isEqualTo(requestType.equals("VOID") ? "VOIDED" : "WAIVED");
        }
    }

    @Test void confirmedPaymentHasAnOwnerOnlyReceiptAndRefundRequiresIndependentApproval() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001"); var student = login("20260001");
        var bill = publish(operator, reviewer); long id = bill.path("id").asLong(); String order = bill.path("orderNo").asText();
        assertThat(student.get("/api/payments/mine/" + id + "/receipt").statusCode()).isEqualTo(409);
        assertThat(ok(student.post("/api/payments/mine/" + id + "/pay", Map.of())).path("qrCode").asText()).startsWith("https://qr.alipay.com/");
        paid.add(order);
        var settled = ok(student.get("/api/payments/mine/" + id));
        assertThat(settled.path("status").asText()).isEqualTo("PAID");
        assertThat(student.get("/api/payments/mine/" + id + "/receipt").body()).startsWith("%PDF");
        assertThat(login("20260002").get("/api/payments/mine/" + id + "/receipt").statusCode()).isEqualTo(404);
        var request = ok(student.post("/api/payments/mine/" + id + "/refund", Map.of("requestKey", UUID.randomUUID().toString(), "reason", "本人申请退回本次沙箱测试费用", "version", settled.path("version").asInt())));
        long requestId = request.path("id").asLong();
        ok(reviewer.post("/api/finance/payments/requests/" + requestId + "/actions", command("APPROVE", 0)));
        var refunded = ok(student.get("/api/payments/mine/" + id));
        assertThat(refunded.path("refundStatus").asText()).isEqualTo("REFUNDED");
        assertThat(ok(operator.get("/api/finance/payments/" + id + "/history")).toString()).contains("REFUND");
    }

    @Test void billSearchStatisticsExportAndNotificationsUseTheSamePublishedFacts() throws Exception {
        var operator = login("finance1001"); var reviewer = login("finance2001");
        var input = fields(); String period = "统计-" + UUID.randomUUID(); input.put("billingPeriod", period); input.put("title", "=危险公式测试");
        var draft = ok(operator.post("/api/finance/payments", input)); long id = draft.path("id").asLong();
        var submitted = action(operator, id, "SUBMIT", 0); action(reviewer, id, "APPROVE", submitted.path("version").asInt());
        var page = ok(operator.get("/api/finance/payments?billingPeriod=" + period + "&pageSize=20"));
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("summary").path("unpaidAmountCents").asLong()).isEqualTo(1250);
        assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(id);
        var csv = operator.get("/api/finance/payments/export?billingPeriod=" + period);
        assertThat(csv.statusCode()).isEqualTo(200); assertThat(csv.body()).contains("'=危险公式测试", period);
        assertThat(operator.get("/api/finance/payments?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(operator.get("/api/finance/payments?status=unknown").statusCode()).isEqualTo(400);
        var current = ok(operator.get("/api/finance/payments/" + id));
        var reminder = command("REMIND", current.path("version").asInt());
        ok(operator.post("/api/finance/payments/" + id + "/actions", reminder));
        ok(operator.post("/api/finance/payments/" + id + "/actions", reminder));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE source_type='PAYMENT' AND source_id=:id AND title LIKE '%缴费提醒%'").param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test void academicStaffCanProvideAnInScopeBasisButCannotPublishCharges() throws Exception {
        var academic = login("academic1001");
        var input = fields(); input.put("quantity", "1");
        var basis = ok(academic.post("/api/payment-bases", input));
        assertThat(basis.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(academic.post("/api/finance/payments", input).statusCode()).isEqualTo(403);
        input.put("categoryCode", "WATER");
        input.put("requestKey", UUID.randomUUID().toString());
        assertThat(academic.post("/api/payment-bases", input).statusCode()).isEqualTo(403);
        assertThat(login("dorm1001").post("/api/payment-bases", fields()).statusCode()).isEqualTo(403);
    }

    @Test void financeRoleRemovalCannotAbandonOwnedDraftsOrTheLastEligibleReviewer() throws Exception {
        var operator = login("finance1001"); ok(operator.post("/api/finance/payments", fields()));
        var admin = login("admin");
        var users = ok(admin.get("/api/admin/foundation/users?q=finance1001")); var user = users.path("items").get(0);
        var response = admin.post("/api/admin/foundation/users/" + user.path("id").asLong() + "/state",
            Map.of("enabled", false, "version", user.path("version").asInt(), "reason", "测试常规离岗交接保护"));
        assertThat(response.statusCode()).isEqualTo(409);
    }
    @Test void batchDraftCreationIsAtomicAndRetriesDoNotDuplicateStudentBills() throws Exception {
        var operator=login("finance1001");String key=UUID.randomUUID().toString();
        var command=new HashMap<String,Object>(Map.of("requestKey",key,"usernames",java.util.List.of("20260001","20260002"),"template",fields()));
        var first=ok(operator.post("/api/finance/payments/batch",command));assertThat(first.size()).isEqualTo(2);
        assertThat(ok(operator.post("/api/finance/payments/batch",command))).isEqualTo(first);
        long before=jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single();
        command.put("requestKey",UUID.randomUUID().toString());command.put("usernames",java.util.List.of("20260001","missing-student"));
        assertThat(operator.post("/api/finance/payments/batch",command).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single()).isEqualTo(before);
    }

    private JsonNode publish(Browser operator, Browser reviewer) throws Exception {
        var draft = ok(operator.post("/api/finance/payments", fields())); long id = draft.path("id").asLong();
        var submitted = action(operator, id, "SUBMIT", draft.path("version").asInt());
        return action(reviewer, id, "APPROVE", submitted.path("version").asInt());
    }
    private JsonNode action(Browser browser, long id, String action, int version) throws Exception {
        return ok(browser.post("/api/finance/payments/" + id + "/actions", command(action, version)));
    }
    private Map<String, Object> command(String action, int version) {
        return Map.of("action", action, "version", version, "requestKey", UUID.randomUUID().toString(), "reason", "核对收费依据后执行本次演示操作");
    }
    private HashMap<String, Object> fields() {
        var input = legacyFields(); input.put("billingPeriod", "2026—2027 学年");
        input.put("dueDate", LocalDate.now().plusDays(30).toString()); input.put("basis", "本次为独立演示收费，已核对收费依据与金额"); return input;
    }
    private HashMap<String, Object> legacyFields() {
        return new HashMap<>(Map.of("requestKey", UUID.randomUUID().toString(), "username", "20260001", "categoryCode", "TUITION", "title", "独立演示学费", "amountCents", 1250));
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
    private static HttpServer gateway() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/payments", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath(); String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String expected = RemotePayClient.sign("local-finance-contract-token", exchange.getRequestMethod(), path,
                        exchange.getRequestHeaders().getFirst("X-Pay-Timestamp"), exchange.getRequestHeaders().getFirst("X-Pay-Nonce"), body);
                    if (!expected.equals(exchange.getRequestHeaders().getFirst("X-Pay-Signature"))) { exchange.sendResponseHeaders(401, -1); return; }
                    JsonNode input = body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
                    String[] parts = path.split("/"); String order = parts.length > 3 ? parts[3] : input.path("orderNo").asText();
                    if (parts.length == 3 && "POST".equals(exchange.getRequestMethod())) amounts.put(order, input.path("amount").asText());
                    String state = path.endsWith("/refunds") ? "REFUNDED" : path.endsWith("/close") ? "CLOSED" : paid.contains(order) ? "PAID" : "PENDING";
                    var output = new HashMap<String, Object>(); output.put("orderNo", order); output.put("amount", amounts.getOrDefault(order, "12.50"));
                    output.put("status", state); output.put("qrCode", "https://qr.alipay.com/local-test"); output.put("tradeNo", "trade-" + order);
                    output.put("requestNo", input.path("requestNo").asText());
                    byte[] bytes = JSON.writeValueAsBytes(output); exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
