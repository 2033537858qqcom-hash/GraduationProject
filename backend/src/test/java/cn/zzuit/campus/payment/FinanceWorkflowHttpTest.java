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
    @Test void missingActionsAndRequestTypesAreRejectedAsInvalidInput() throws Exception {
        var operator=login("finance1001");var reviewer=login("finance2001");
        var bill=publish(operator,reviewer);long id=bill.path("id").asLong();
        var invalid=new HashMap<String,Object>(command("SUBMIT",bill.path("version").asInt()));invalid.remove("action");
        assertThat(operator.post("/api/finance/payments/"+id+"/actions",invalid).statusCode()).isEqualTo(400);
        invalid=new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",bill.path("version").asInt(),"reason","缺少处理类型应当被拒绝"));
        assertThat(operator.post("/api/finance/payments/"+id+"/requests",invalid).statusCode()).isEqualTo(400);
    }
    @Test void statementPreviewReportsDifferencesAndNeverTreatsUploadedDataAsPaymentConfirmation() throws Exception {
        var operator=login("finance1001");var reviewer=login("finance2001");var bill=publish(operator,reviewer);
        long id=bill.path("id").asLong();String order=bill.path("orderNo").asText();
        String csv="\uFEFF订单号,金额（元）,交易号,支付状态\r\n"+order+",12.50,,UNPAID\r\n"+order+",12.50,FAKE-TRADE,PAID\r\nUNKNOWN-ORDER-0001,10.00,,UNPAID";
        var preview=ok(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv",csv)));
        assertThat(preview.path("matchedCount").asInt()).isEqualTo(1);
        assertThat(preview.path("duplicateCount").asInt()).isEqualTo(1);
        assertThat(preview.path("notFoundCount").asInt()).isEqualTo(1);
        assertThat(ok(operator.get("/api/finance/payments/"+id)).path("status").asText()).isEqualTo("UNPAID");
        String wrong="商户订单号,订单金额（元）,支付宝交易号,交易状态\n"+order+",99.00,FAKE-TRADE,交易成功";
        var mismatch=ok(reviewer.post("/api/finance/payments/reconciliation/preview",Map.of("csv",wrong)));
        assertThat(mismatch.path("differenceCount").asInt()).isEqualTo(1);
        assertThat(mismatch.toString()).contains("金额", "支付状态");
        assertThat(login("20260001").post("/api/finance/payments/reconciliation/preview",Map.of("csv",csv)).statusCode()).isEqualTo(403);
        assertThat(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv","错误,表头\n1,2")).statusCode()).isEqualTo(400);
        assertThat(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv","订单号,金额（元）,交易号,支付状态\n\"未闭合")).statusCode()).isEqualTo(400);
    }

    @Test void closedOrdersReconcileOnlyAfterConfirmedVoidOrWaiver() throws Exception {
        var operator=login("finance1001"); var reviewer=login("finance2001"); var student=login("20260001");
        for(String type : new String[]{"VOID","WAIVER"}) {
            var bill=publish(operator,reviewer); long id=bill.path("id").asLong(); String order=bill.path("orderNo").asText();
            ok(student.post("/api/payments/mine/"+id+"/pay",Map.of()));
            var pendingCsv="订单号,金额（元）,交易号,支付状态\n"+order+",12.50,,CLOSED";
            assertThat(ok(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv",pendingCsv))).path("differenceCount").asInt()).isEqualTo(1);
            var request=ok(operator.post("/api/finance/payments/"+id+"/requests",Map.of("requestKey",UUID.randomUUID().toString(),"type",type,"reason","隔离验证关单与对账一致性","version",bill.path("version").asInt())));
            ok(reviewer.post("/api/finance/payments/requests/"+request.path("id").asLong()+"/actions",command("APPROVE",0)));
            var result=ok(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv",pendingCsv)));
            assertThat(result.path("matchedCount").asInt()).as(type).isEqualTo(1);
            assertThat(result.path("items").get(0).path("localStatus").asText()).isEqualTo("CLOSED");
            var wrongAmount="订单号,金额（元）,交易号,支付状态\n"+order+",99.00,,CLOSED";
            assertThat(ok(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv",wrongAmount))).path("differenceCount").asInt()).isEqualTo(1);
        }
        var neverStarted=publish(operator,reviewer); long id=neverStarted.path("id").asLong();
        var request=ok(operator.post("/api/finance/payments/"+id+"/requests",Map.of("requestKey",UUID.randomUUID().toString(),"type","VOID","reason","未发起支付的作废账单验证","version",neverStarted.path("version").asInt())));
        ok(reviewer.post("/api/finance/payments/requests/"+request.path("id").asLong()+"/actions",command("APPROVE",0)));
        var statement="订单号,金额（元）,交易号,支付状态\n"+neverStarted.path("orderNo").asText()+",12.50,,CLOSED";
        assertThat(ok(operator.post("/api/finance/payments/reconciliation/preview",Map.of("csv",statement))).path("differenceCount").asInt()).isEqualTo(1);
    }

    @Test void dormRechargeCreditsOnceAndRoommatesShareOnlyTheirRoomLedger() throws Exception {
        var home=rechargeHome(); var student=login(home.student()); var roommate=login(home.roommate());
        var body=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"meterId",home.meter(),"mode","AMOUNT","value","10.00");
        var bill=ok(student.post("/api/payments/mine/dorm-recharge",body)); long id=bill.path("id").asLong();
        assertThat(ok(student.post("/api/payments/mine/dorm-recharge",body)).path("id").asLong()).isEqualTo(id);
        assertThat(bill.path("amountCents").asLong()).isEqualTo(1000);
        assertThat(ok(roommate.get("/api/payments/mine/dorm-recharge")).path("meters").get(0).path("balance").decimalValue()).isEqualByComparingTo("0");
        ok(student.post("/api/payments/mine/"+id+"/pay",Map.of())); paid.add(bill.path("orderNo").asText());
        ok(student.get("/api/payments/mine/"+id)); ok(student.get("/api/payments/mine/"+id));
        var room=ok(roommate.get("/api/payments/mine/dorm-recharge"));
        assertThat(room.path("meters").get(0).path("balance").decimalValue()).isEqualByComparingTo("16.393442622");
        assertThat(room.path("history").get(0).path("creditedAt").isNull()).isFalse();
        assertThat(roommate.get("/api/payments/mine/"+id).statusCode()).isEqualTo(404);
        assertThat(login("20260002").post("/api/payments/mine/dorm-recharge",body).statusCode()).isIn(404,409);
        var refund=ok(student.post("/api/payments/mine/"+id+"/refund",Map.of("requestKey",UUID.randomUUID().toString(),"version",ok(student.get("/api/payments/mine/"+id)).path("version").asInt(),"reason","隔离测试宿舍演示充值退款")));
        var reviewer=login("finance2001"); var command=command("APPROVE",refund.path("version").asInt());
        ok(reviewer.post("/api/finance/payments/requests/"+refund.path("id").asLong()+"/actions",command));
        ok(reviewer.post("/api/finance/payments/requests/"+refund.path("id").asLong()+"/actions",command));
        assertThat(ok(roommate.get("/api/payments/mine/dorm-recharge")).path("meters").get(0).path("balance").decimalValue()).isEqualByComparingTo("0");
    }
    @Test void dormMeterUpdatesPreserveQuotedOrdersAndRejectMovedOutPayers() throws Exception {
        var home=rechargeHome(); var student=login(home.student()); var op=login("finance1001");
        var body=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"meterId",home.meter(),"mode","QUANTITY","value","10");
        var bill=ok(student.post("/api/payments/mine/dorm-recharge",body));
        assertThat(bill.path("amountCents").asLong()).isEqualTo(610);
        var meter=ok(op.get("/api/finance/payments/dorm-meters")).path("meters");
        JsonNode target=null; for(var item:meter) if(item.path("id").asLong()==home.meter()) target=item;
        assertThat(target).isNotNull();
        ok(op.send("PUT","/api/finance/payments/dorm-meters/"+home.meter(),Map.of("standardId",home.standard(),"enabled",false,"version",target.path("version").asInt(),"reason","隔离测试停用表计")));
        assertThat(student.post("/api/payments/mine/"+bill.path("id").asLong()+"/pay",Map.of()).statusCode()).isEqualTo(409);
        assertThat(ok(student.post("/api/payments/mine/dorm-recharge",body)).path("id").asLong()).isEqualTo(bill.path("id").asLong());
        var disabled=ok(op.get("/api/finance/payments/dorm-meters")).path("meters");
        for(var item:disabled) if(item.path("id").asLong()==home.meter())
            ok(op.send("PUT","/api/finance/payments/dorm-meters/"+home.meter(),Map.of("standardId",home.standard(),"enabled",true,"version",item.path("version").asInt(),"reason","隔离测试重新启用表计")));
        long user=jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",home.student()).query(Long.class).single();
        jdbc.sql("UPDATE housing_assignment SET status='ENDED',current_student_id=NULL,current_bed_id=NULL,ended_at=CURRENT_TIMESTAMP,ended_by=:u,checkout_key=:key,end_reason='测试退宿' WHERE current_student_id=:u").param("u",user).param("key",UUID.randomUUID().toString()).update();
        assertThat(student.post("/api/payments/mine/"+bill.path("id").asLong()+"/pay",Map.of()).statusCode()).isEqualTo(409);
        assertThat(student.get("/api/payments/mine/dorm-recharge").statusCode()).isEqualTo(404);
    }
    @Test void dormRechargeRejectsInvalidPricesRolesAndInput() throws Exception {
        var home=rechargeHome();var student=login(home.student());
        for(String value:new String[]{"1e2","0","-1","1.001","NaN"})
            assertThat(student.post("/api/payments/mine/dorm-recharge",Map.of("requestKey",UUID.randomUUID().toString(),"meterId",home.meter(),"mode","AMOUNT","value",value)).statusCode()).isEqualTo(400);
        assertThat(student.post("/api/finance/payments/dorm-meters",Map.of("roomId",home.room(),"standardId",home.standard(),"meterCode","FORBIDDEN")).statusCode()).isEqualTo(403);
        assertThat(login("finance2001").post("/api/finance/payments/dorm-meters",Map.of("roomId",home.room(),"standardId",home.standard(),"meterCode","FORBIDDEN")).statusCode()).isEqualTo(403);
    }
    @Test void concurrentDormRechargeReplaysCreateOneOrderAndCreditsAccumulate() throws Exception {
        var home=rechargeHome(); var student=login(home.student());
        var body=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"meterId",home.meter(),"mode","AMOUNT","value","6.10");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->ok(student.post("/api/payments/mine/dorm-recharge",body)));
            var two=pool.submit(()->ok(student.post("/api/payments/mine/dorm-recharge",body)));
            var first=one.get();assertThat(two.get().path("id").asLong()).isEqualTo(first.path("id").asLong());
            var other=login(home.roommate());
            var second=ok(other.post("/api/payments/mine/dorm-recharge",Map.of("requestKey",UUID.randomUUID().toString(),"meterId",home.meter(),"mode","AMOUNT","value","6.10")));
            ok(student.post("/api/payments/mine/"+first.path("id").asLong()+"/pay",Map.of()));
            ok(other.post("/api/payments/mine/"+second.path("id").asLong()+"/pay",Map.of()));
            paid.add(first.path("orderNo").asText());paid.add(second.path("orderNo").asText());
            var settledOne=pool.submit(()->ok(student.get("/api/payments/mine/"+first.path("id").asLong())));
            var settledTwo=pool.submit(()->ok(other.get("/api/payments/mine/"+second.path("id").asLong())));
            settledOne.get();settledTwo.get();
            assertThat(ok(student.get("/api/payments/mine/dorm-recharge")).path("meters").get(0).path("balance").decimalValue()).isEqualByComparingTo("20");
        }
    }
    private record RechargeHome(String student,String roommate,long room,long meter,long standard) {}
    private RechargeHome rechargeHome() throws Exception {
        String code="rc"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        jdbc.sql("INSERT INTO dorm_building(campus_id,code,name,provenance) VALUES(1,:code,'充值测试楼','DEMO')").param("code",code).update();
        long building=jdbc.sql("SELECT id FROM dorm_building WHERE code=:c").param("c",code).query(Long.class).single();
        jdbc.sql("INSERT INTO dorm_room(building_id,code,name,floor_label) VALUES(:b,'101','101','1层')").param("b",building).update();
        long room=jdbc.sql("SELECT id FROM dorm_room WHERE building_id=:b").param("b",building).query(Long.class).single();
        String[] names={code+"a",code+"b"};
        for(String name:names) {
            jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :n,password_hash,'充值测试学生',:n,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username='20260001'").param("n",name).update();
            long user=jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single();
            jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'STUDENT')").param("u",user).update();
            jdbc.sql("INSERT INTO dorm_bed(room_id,code,label,status) VALUES(:r,:n,'床位','OCCUPIED')").param("r",room).param("n",name).update();
            long bed=jdbc.sql("SELECT id FROM dorm_bed WHERE room_id=:r AND code=:n").param("r",room).param("n",name).query(Long.class).single();
            jdbc.sql("INSERT INTO housing_assignment(student_id,student_username,student_name,bed_id,status,request_key,current_student_id,current_bed_id,campus_name,building_name,room_name,floor_label,bed_label,assigned_by) VALUES(:u,:n,'充值测试学生',:d,'CURRENT',:n,:u,:d,'东校区','充值测试楼','101','1层','床位',:u)").param("u",user).param("n",name).param("d",bed).update();
        }
        var op=login("finance1001");var rev=login("finance2001");
        var standard=ok(op.post("/api/finance/payments/standards",Map.of("requestKey",UUID.randomUUID().toString(),"name","演示电价","categoryCode","ELECTRICITY","unit","度","unitPriceCents",61,"reason","隔离测试已核定电价")));
        long s=standard.path("id").asLong();ok(rev.post("/api/finance/payments/standards/"+s+"/actions",command("APPROVE",0)));
        long meter=ok(op.post("/api/finance/payments/dorm-meters",Map.of("roomId",room,"standardId",s,"meterCode",code))).path("id").asLong();
        return new RechargeHome(names[0],names[1],room,meter,s);
    }

    @Test void financeCanDefineAndRetireCustomFeeItemsWithoutAlteringHistoricalBills() throws Exception {
        var operator=login("finance1001");var reviewer=login("finance2001");var student=login("20260001");
        var item=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"name","校服费","description","按学校核定的校服费用","reason","建立独立收费项目供复核");
        assertThat(student.post("/api/finance/payments/categories",item).statusCode()).isEqualTo(403);
        assertThat(reviewer.post("/api/finance/payments/categories",item).statusCode()).isEqualTo(403);
        var category=ok(operator.post("/api/finance/payments/categories",item));String code=category.path("code").asText();
        assertThat(category.path("enabled").asBoolean()).isTrue();
        assertThat(ok(operator.post("/api/finance/payments/categories",item)).path("code").asText()).isEqualTo(code);
        var modified=new HashMap<>(item);modified.put("name","不同项目");assertThat(operator.post("/api/finance/payments/categories",modified).statusCode()).isEqualTo(409);
        var standard=ok(operator.post("/api/finance/payments/standards",Map.of("requestKey",UUID.randomUUID().toString(),"name","校服每套","categoryCode",code,"unit","套","unitPriceCents",16000,"reason","学校核定的演示收费标准")));
        ok(reviewer.post("/api/finance/payments/standards/"+standard.path("id").asLong()+"/actions",command("APPROVE",0)));
        var input=fields();input.put("categoryCode",code);input.put("standardId",standard.path("id").asLong());input.put("quantity","1");
        var draft=ok(operator.post("/api/finance/payments",input));var submitted=action(operator,draft.path("id").asLong(),"SUBMIT",draft.path("version").asInt());
        var bill=action(reviewer,draft.path("id").asLong(),"APPROVE",submitted.path("version").asInt());
        assertThat(bill.path("amountCents").asLong()).isEqualTo(16000);
        var disable=Map.<String,Object>of("enabled",false,"version",0,"reason","停用演示收费项目");
        assertThat(operator.send("PUT","/api/finance/payments/categories/"+code,disable).statusCode()).isEqualTo(409);
        ok(operator.post("/api/finance/payments/standards/"+standard.path("id").asLong()+"/actions",command("RETIRE",1)));
        assertThat(ok(operator.send("PUT","/api/finance/payments/categories/"+code,disable)).path("enabled").asBoolean()).isFalse();
        var next=fields();next.put("categoryCode",code);assertThat(operator.post("/api/finance/payments",next).statusCode()).isEqualTo(409);
        assertThat(ok(student.get("/api/payments/mine/"+bill.path("id").asLong())).path("categoryName").asText()).isEqualTo("校服费");
        assertThat(ok(operator.get("/api/finance/payments?categoryCode="+code)).path("total").asInt()).isEqualTo(1);
        assertThat(operator.send("PUT","/api/finance/payments/categories/"+code,disable).statusCode()).isEqualTo(409);
    }
    @Test void protectedCategoriesCannotBeDisabledAndCategoryNameMustBeUnique() throws Exception {
        var op=login("finance1001");
        assertThat(op.send("PUT","/api/finance/payments/categories/OTHER",Map.of("enabled",false,"version",0,"reason","尝试停用历史分类" )).statusCode()).isEqualTo(409);
        assertThat(op.post("/api/finance/payments/categories",Map.of("requestKey",UUID.randomUUID().toString(),"name","学费","description","重复名称测试","reason","测试重复收费项目" )).statusCode()).isEqualTo(409);
    }
    @Test void categoryWithSubmittedBasisCannotBeDisabledUntilItIsHandled() throws Exception {
        var op=login("finance1001");
        var category=ok(op.post("/api/finance/payments/categories",Map.of("requestKey",UUID.randomUUID().toString(),"name","体检专项","description","演示体检费用","reason","测试依据交接保护")));
        String code=category.path("code").asText();
        long student=jdbc.sql("SELECT id FROM app_user WHERE username='20260001'").query(Long.class).single();
        long author=jdbc.sql("SELECT id FROM app_user WHERE username='academic1001'").query(Long.class).single();
        jdbc.sql("INSERT INTO payment_basis(request_key,student_id,category_code,billing_period,quantity,basis,source_reference,created_by) VALUES(:key,:student,:code,'测试账期','1','待处理的收费依据','测试来源',:author)")
            .param("key",UUID.randomUUID().toString()).param("student",student).param("code",code).param("author",author).update();
        var change=Map.<String,Object>of("enabled",false,"version",0,"reason","测试停用项目保护");
        assertThat(op.send("PUT","/api/finance/payments/categories/"+code,change).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE payment_basis SET status='RETURNED' WHERE category_code=:code").param("code",code).update();
        assertThat(ok(op.send("PUT","/api/finance/payments/categories/"+code,change)).path("enabled").asBoolean()).isFalse();
    }
    @Test void futureOrExpiredStandardsCannotBeUsedToCreateBills() throws Exception {
        var operator=login("finance1001");var reviewer=login("finance2001");
        var today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var input=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"name","下学年校区水价","categoryCode","WATER","unit","吨","unitPriceCents",320,"reason","按照新学年演示标准核定","effectiveFrom",today.plusDays(1).toString(),"effectiveTo",today.plusDays(30).toString()));
        var standard=ok(operator.post("/api/finance/payments/standards",input));long id=standard.path("id").asLong();
        var active=ok(reviewer.post("/api/finance/payments/standards/"+id+"/actions",command("APPROVE",0)));
        assertThat(active.path("available").asBoolean()).isFalse();
        var bill=fields();bill.put("categoryCode","WATER");bill.put("standardId",id);bill.put("quantity","1");
        assertThat(operator.post("/api/finance/payments",bill).statusCode()).isEqualTo(409);
        input.put("requestKey",UUID.randomUUID().toString());input.put("name","历史校区水价");input.put("effectiveFrom",today.minusDays(5).toString());input.put("effectiveTo",today.minusDays(1).toString());
        var expired=ok(operator.post("/api/finance/payments/standards",input));
        var expiredActive=ok(reviewer.post("/api/finance/payments/standards/"+expired.path("id").asLong()+"/actions",command("APPROVE",0)));
        assertThat(expiredActive.path("available").asBoolean()).isFalse();
        bill.put("requestKey",UUID.randomUUID().toString());bill.put("standardId",expired.path("id").asLong());
        assertThat(operator.post("/api/finance/payments",bill).statusCode()).isEqualTo(409);
        input.put("requestKey",UUID.randomUUID().toString());input.put("effectiveFrom",today.plusDays(2).toString());input.put("effectiveTo",today.plusDays(1).toString());
        assertThat(operator.post("/api/finance/payments/standards",input).statusCode()).isEqualTo(400);
    }
    @Test void dormMeterOnlyUsesCurrentlyEffectivePricesButExistingOrdersKeepTheirQuote() throws Exception {
        var home=rechargeHome();var student=login(home.student());var operator=login("finance1001");var reviewer=login("finance2001");
        var request=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"meterId",home.meter(),"mode","AMOUNT","value","6.10");
        var bill=ok(student.post("/api/payments/mine/dorm-recharge",request));
        ok(operator.post("/api/finance/payments/standards/"+home.standard()+"/actions",command("RETIRE",1)));
        var overview=ok(student.get("/api/payments/mine/dorm-recharge"));
        assertThat(overview.path("meters").get(0).path("standardAvailable").asBoolean()).isFalse();
        var next=new HashMap<>(request);next.put("requestKey",UUID.randomUUID().toString());
        assertThat(student.post("/api/payments/mine/dorm-recharge",next).statusCode()).isEqualTo(404);
        assertThat(student.post("/api/payments/mine/"+bill.path("id").asLong()+"/pay",Map.of()).statusCode()).isEqualTo(200);
        var tomorrow=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        var later=ok(operator.post("/api/finance/payments/standards",Map.of("requestKey",UUID.randomUUID().toString(),"name","未来电价","categoryCode","ELECTRICITY","unit","度","unitPriceCents",80,"reason","下期收费标准","effectiveFrom",tomorrow.toString())));
        ok(reviewer.post("/api/finance/payments/standards/"+later.path("id").asLong()+"/actions",command("APPROVE",0)));
        var old=overview.path("meters").get(0);
        assertThat(operator.send("PUT","/api/finance/payments/dorm-meters/"+home.meter(),Map.of("standardId",later.path("id").asLong(),"enabled",true,"version",old.path("version").asInt(),"reason","未来标准不得提前启用" )).statusCode()).isEqualTo(409);
    }

    @Test void reconciliationDifferencesHaveIndependentReviewWithoutChangingPaymentState() throws Exception {
        var operator=login("finance1001");var reviewer=login("finance2001");var student=login("20260001");
        var bill=publish(operator,reviewer);long billId=bill.path("id").asLong();String order=bill.path("orderNo").asText();
        String csv="订单号,金额（元）,交易号,支付状态\n"+order+",99.00,FAKE,PAID\nMISSING-ORDER-001,1.00,,UNPAID";
        var input=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"csv",csv);
        assertThat(student.get("/api/finance/payments/reconciliation/cases").statusCode()).isEqualTo(403);
        assertThat(reviewer.post("/api/finance/payments/reconciliation/cases",input).statusCode()).isEqualTo(403);
        var batch=ok(operator.post("/api/finance/payments/reconciliation/cases",input));
        assertThat(batch.path("items").size()).isEqualTo(2);
        assertThat(ok(operator.post("/api/finance/payments/reconciliation/cases",input)).path("batchId").asLong()).isEqualTo(batch.path("batchId").asLong());
        var changed=new HashMap<>(input);changed.put("csv",csv+"\nMISSING-ORDER-002,1.00,,UNPAID");
        assertThat(operator.post("/api/finance/payments/reconciliation/cases",changed).statusCode()).isEqualTo(409);
        var item=batch.path("items").get(0);long id=item.path("id").asLong();
        assertThat(item.path("status").asText()).isEqualTo("OPEN");
        assertThat(ok(operator.get("/api/workbench/tasks?domain=PAYMENT")).toString()).contains("RECON-"+id);
        var startCommand=command("START",0);
        var started=ok(operator.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",startCommand));
        assertThat(ok(operator.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",startCommand)).path("version").asInt()).isEqualTo(started.path("version").asInt());
        assertThat(started.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(operator.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("SUBMIT",0)).statusCode()).isEqualTo(409);
        var submitted=ok(operator.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("SUBMIT",started.path("version").asInt())));
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(ok(reviewer.get("/api/workbench/tasks?domain=PAYMENT")).toString()).contains("RECON-"+id);
        assertThat(operator.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("CLOSE",submitted.path("version").asInt())).statusCode()).isEqualTo(403);
        var closed=ok(reviewer.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("CLOSE",submitted.path("version").asInt())));
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(ok(operator.get("/api/finance/payments/reconciliation/cases?status=CLOSED")).path("items").toString()).contains("\"id\":"+id);
        assertThat(operator.get("/api/finance/payments/reconciliation/cases?status=UNKNOWN").statusCode()).isEqualTo(400);
        assertThat(ok(operator.get("/api/finance/payments/"+billId)).path("status").asText()).isEqualTo("UNPAID");
        var page=ok(operator.get("/api/finance/payments/reconciliation/cases?page=1&pageSize=20"));
        assertThat(page.path("total").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("items").toString()).contains("\"id\":"+id);
        assertThat(ok(operator.get("/api/finance/payments/reconciliation/cases/"+id+"/history")).size()).isGreaterThanOrEqualTo(4);
    }
    @Test void reconciliationCaseCanBeTakenOverAfterStaffChangeAndReturnedForRework() throws Exception {
        var op=login("finance1001");var reviewer=login("finance2001");
        String name="recon_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) SELECT :name,password_hash,'对账经办',:name,campus_id,department_id,TRUE FROM app_user WHERE username='finance1001'").param("name",name).update();
        long user=jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'FINANCE_OPERATOR')").param("id",user).update();
        try {
            var replacement=login(name);String csv="订单号,金额（元）,交易号,支付状态\nMISSING-TEST-001,1.00,,UNPAID";
            var batch=ok(op.post("/api/finance/payments/reconciliation/cases",Map.of("requestKey",UUID.randomUUID().toString(),"csv",csv)));
            long id=batch.path("items").get(0).path("id").asLong();
            var started=ok(op.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("START",0)));
            assertThat(replacement.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("SUBMIT",started.path("version").asInt())).statusCode()).isEqualTo(409);
            var takeover=ok(replacement.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("TAKE_OVER",started.path("version").asInt())));
            assertThat(takeover.path("assigneeId").asLong()).isEqualTo(user);
            var submitted=ok(replacement.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("SUBMIT",takeover.path("version").asInt())));
            var returned=ok(reviewer.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("RETURN",submitted.path("version").asInt())));
            assertThat(returned.path("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(ok(op.get("/api/finance/payments/reconciliation/cases/"+id+"/history")).toString()).contains("TAKE_OVER","RETURN");
        } finally { jdbc.sql("UPDATE app_user SET enabled=FALSE WHERE id=:id").param("id",user).update(); }
    }
    @Test void reconciliationPendingReviewProtectsLastReviewerAndRaisesEmergencyException() throws Exception {
        var op=login("finance1001");var admin=login("admin");
        String csv="订单号,金额（元）,交易号,支付状态\nMISSING-REVIEW-001,1.00,,UNPAID";
        var batch=ok(op.post("/api/finance/payments/reconciliation/cases",Map.of("requestKey",UUID.randomUUID().toString(),"csv",csv)));
        long id=batch.path("items").get(0).path("id").asLong();
        var started=ok(op.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("START",0)));
        ok(op.post("/api/finance/payments/reconciliation/cases/"+id+"/actions",command("SUBMIT",started.path("version").asInt())));
        long reviewer=jdbc.sql("SELECT id FROM app_user WHERE username='finance2001'").query(Long.class).single();
        int version=jdbc.sql("SELECT version FROM app_user WHERE id=:id").param("id",reviewer).query(Integer.class).single();
        var state=Map.<String,Object>of("enabled",false,"emergency",false,"version",version,"reason","对账岗位交接验证");
        assertThat(admin.post("/api/admin/foundation/users/"+reviewer+"/state",state).statusCode()).isEqualTo(409);
        try {
            ok(admin.post("/api/admin/foundation/users/"+reviewer+"/state",Map.of("enabled",false,"emergency",true,"version",version,"reason","对账岗位应急撤权验证")));
            var exceptions=ok(admin.get("/api/admin/foundation/responsibility-exceptions"));
            boolean found=false;for(var item:exceptions) if(item.path("code").asText().equals("RECON_REVIEW") && item.path("count").asLong()>0) found=true;
            assertThat(found).isTrue();
        } finally { jdbc.sql("UPDATE app_user SET enabled=TRUE,version=version+1,security_version=security_version+1 WHERE id=:id").param("id",reviewer).update(); }
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
