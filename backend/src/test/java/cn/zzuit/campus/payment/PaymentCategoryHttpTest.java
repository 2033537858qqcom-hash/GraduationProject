package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.ArrayList;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-categories;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true", "app.payment.enabled=false", "app.cos.enabled=false",
    "app.agent.deepseek.enabled=false", "app.repair.reminders.enabled=false"})
class PaymentCategoryHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json = JsonMapper.builder().build();

    @Test void catalogRequiresLoginAndTheRelevantPermission() throws Exception {
        assertThat(new Browser().get("/api/payments/categories").statusCode()).isEqualTo(401);
        assertThat(new Browser().get("/api/admin/payments/categories").statusCode()).isEqualTo(401);
        assertThat(login("20260001").get("/api/admin/payments/categories").statusCode()).isEqualTo(403);
        assertThat(login("teacher1001").get("/api/payments/categories").statusCode()).isEqualTo(403);
    }

    @Test void administratorsAndStudentsReceiveTheSameOrderedPresetCatalog() throws Exception {
        var adminCatalog = ok(login("finance1001").get("/api/finance/payments/categories"));
        var studentCatalog = ok(login("20260001").get("/api/payments/categories"));
        assertThat(studentCatalog).isEqualTo(adminCatalog);
        assertThat(values(adminCatalog, "code")).containsExactly(
            "TUITION", "ACCOMMODATION", "WATER", "ELECTRICITY", "UTILITIES", "TEXTBOOK", "VENUE", "OTHER");
        assertThat(values(adminCatalog, "name")).containsExactly(
            "学费", "住宿费", "水费", "电费", "水电费", "教材费", "场地使用费", "其他费用");
        for (var category : adminCatalog) assertThat(category.path("description").asText()).isNotBlank();
    }

    @Test void everyPresetIsStoredAndReturnedInTheOwnersListAndDetail() throws Exception {
        var admin = login("finance1001");
        var student = login("20260001");
        var other = login("20260002");
        for (var code : List.of("TUITION", "ACCOMMODATION", "WATER", "ELECTRICITY", "UTILITIES", "TEXTBOOK", "VENUE", "OTHER")) {
            var bill = published(admin, fields(code));
            long id = bill.path("id").asLong();
            assertThat(bill.path("categoryCode").asText()).isEqualTo(code);
            assertThat(bill.path("categoryName").asText()).isNotBlank();
            assertThat(jdbc.sql("SELECT category_code FROM payment_bill WHERE id=:id")
                .param("id", id).query(String.class).single()).isEqualTo(code);
            assertThat(values(ok(student.get("/api/payments/mine")), "categoryCode")).contains(code);
            assertThat(ok(student.get("/api/payments/mine/" + id)).path("categoryCode").asText()).isEqualTo(code);
            assertThat(other.get("/api/payments/mine/" + id).statusCode()).isEqualTo(404);
        }
        assertThat(ok(other.get("/api/payments/mine")).size()).isZero();
    }

    @Test void unknownAndBlankCategoriesAreRejectedWithoutCreatingBills() throws Exception {
        var admin = login("finance1001");
        long before = jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single();
        for (var code : List.of("UNKNOWN", "", " ", "tuition", "TUITION' OR 1=1")) {
            var response = admin.post("/api/finance/payments", fields(code));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_CATEGORY");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single()).isEqualTo(before);
    }

    @Test void retriesPreserveTheOrderAndCannotChangeItsCategory() throws Exception {
        var admin = login("finance1001");
        var input = fields("TUITION");
        var first = ok(admin.post("/api/finance/payments", input));
        var repeated = ok(admin.post("/api/finance/payments", input));
        assertThat(repeated.path("orderNo").asText()).isEqualTo(first.path("orderNo").asText());
        input.put("categoryCode", "ACCOMMODATION");
        assertThat(admin.post("/api/finance/payments", input).statusCode()).isEqualTo(409);
    }

    @Test void legacyRequestsDefaultToOtherAndRetainTheirTitleAndAmount() throws Exception {
        var input = fields("OTHER");
        input.remove("categoryCode");
        var bill = ok(login("finance1001").post("/api/finance/payments", input));
        assertThat(bill.path("categoryCode").asText()).isEqualTo("OTHER");
        assertThat(bill.path("categoryName").asText()).isEqualTo("其他费用");
        assertThat(bill.path("title").asText()).isEqualTo(input.get("title"));
        assertThat(bill.path("amountCents").asLong()).isEqualTo(1250);
    }

    @Test void categorySelectionDoesNotGrantStudentsBillCreationOrBypassCsrf() throws Exception {
        assertThat(login("20260001").post("/api/admin/payments", fields("TUITION")).statusCode()).isEqualTo(403);
        var admin = login("finance1001");
        var request = admin.builder("/api/finance/payments").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(fields("TUITION")))).build();
        assertThat(admin.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    private HashMap<String, Object> fields(String category) {
        return new HashMap<>(Map.of("requestKey", UUID.randomUUID().toString(), "username", "20260001",
            "title", "2026 学年演示费用", "amountCents", 1250, "categoryCode", category,
            "billingPeriod","独立分类测试账期","dueDate",java.time.LocalDate.now().plusDays(30).toString(),"basis","分类持久化测试的明确收费依据"));
    }
    private JsonNode published(Browser operator,Map<String,Object> fields) throws Exception {
        var draft=ok(operator.post("/api/finance/payments",fields));long id=draft.path("id").asLong();
        var submitted=ok(operator.post("/api/finance/payments/"+id+"/actions",Map.of("action","SUBMIT","version",0,"requestKey",UUID.randomUUID().toString(),"reason","经办核对分类测试账单")));
        return ok(login("finance2001").post("/api/finance/payments/"+id+"/actions",Map.of("action","APPROVE","version",submitted.path("version").asInt(),"requestKey",UUID.randomUUID().toString(),"reason","独立复核分类测试账单")));
    }
    private List<String> values(JsonNode rows, String field) {
        var values = new ArrayList<String>();
        for (var row : rows) values.add(row.path(field).asText());
        return values;
    }
    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private Browser login(String username) throws Exception {
        var browser = new Browser();
        browser.csrf();
        ok(browser.post("/api/auth/login", Map.of("username", username, "password", "Demo@2026")));
        browser.csrf();
        return browser;
    }
    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String header, token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }
        HttpResponse<String> get(String path) throws Exception {
            return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {
            var data = json.readTree(get("/api/auth/csrf").body());
            header = data.path("headerName").asText(); token = data.path("token").asText();
        }
        HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
            return http.send(builder(path).header(header, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
