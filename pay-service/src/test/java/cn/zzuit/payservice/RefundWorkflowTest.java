package cn.zzuit.payservice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
class RefundWorkflowTest {
    @TestConfiguration static class Configuration { @Bean @Primary FakeGateway gateway() { return new FakeGateway(); } }
    @Autowired PaymentStore store;
    @Autowired FakeGateway gateway;
    @Autowired JdbcClient jdbc;

    @BeforeEach void reset() {
        jdbc.sql("DELETE FROM pay_refund").update(); jdbc.sql("DELETE FROM pay_order").update();
        gateway.next = null; gateway.precreates = 0; gateway.valid = true;
        gateway.refundFailure=false;gateway.refunds=0;
    }
    @Test void closeIsConfirmedAndPaidOrdersCannotBeVoided() {
        store.create("CLOSE0001", "12.50", "独立关单演示");
        assertEquals("CLOSED", store.closeOrder("CLOSE0001").status());
        assertEquals("CLOSED", store.closeOrder("CLOSE0001").status());
        store.create("CLOSE0002", "12.50", "已付订单不可关单"); markPaid("CLOSE0002");
        assertEquals("PAID", store.closeOrder("CLOSE0002").status());
    }
    @Test void refundsHaveAnImmutableIdempotencyKeyAndCannotExceedTheOriginalPayment() {
        store.create("REFUND001", "12.50", "独立退款演示"); markPaid("REFUND001");
        var first = store.refund("REFUND001", "REFUND-REQUEST-0001", "12.50", "复核通过的演示退款");
        assertEquals("REFUNDED", first.status());
        assertEquals(first, store.refund("REFUND001", "REFUND-REQUEST-0001", "12.50", "复核通过的演示退款"));
        assertThrows(ResponseStatusException.class, () -> store.refund("REFUND001", "REFUND-REQUEST-0001", "1.00", "变更金额"));
        assertThrows(ResponseStatusException.class, () -> store.refund("REFUND001", "REFUND-REQUEST-0002", "12.50", "重复整单退款"));
    }
    @Test void unconfirmedOrUnpaidOrdersCannotBeRefunded() {
        store.create("REFUND002", "12.50", "尚未支付");
        assertThrows(ResponseStatusException.class, () -> store.refund("REFUND002", "REFUND-REQUEST-0003", "12.50", "不能退未付订单"));
        assertThrows(ResponseStatusException.class, () -> store.refund("NOT-FOUND", "REFUND-REQUEST-0004", "12.50", "不存在订单"));
    }
    @Test void anUnconfirmedRefundKeepsItsReservationAndRetryUsesTheSameGatewayRequest() {
        store.create("REFUND003", "12.50", "平台超时退款"); markPaid("REFUND003");gateway.refundFailure=true;
        assertThrows(ResponseStatusException.class,()->store.refund("REFUND003","REFUND-REQUEST-TIMEOUT","12.50","保留原请求核对退款结果"));
        assertEquals("PENDING",jdbc.sql("SELECT status FROM pay_refund WHERE request_no='REFUND-REQUEST-TIMEOUT'").query(String.class).single());
        assertThrows(ResponseStatusException.class,()->store.refund("REFUND003","REFUND-REQUEST-ANOTHER","12.50","不能换请求重复退款"));
        gateway.refundFailure=false;
        assertEquals("REFUNDED",store.refund("REFUND003","REFUND-REQUEST-TIMEOUT","12.50","保留原请求核对退款结果").status());
        assertEquals(2,gateway.refunds);
    }
    private void markPaid(String order) {
        assertTrue(store.notification(Map.of("app_id", "app1", "out_trade_no", order, "total_amount", "12.50",
            "trade_status", "TRADE_SUCCESS", "trade_no", "T" + order, "sign", "ok", "sign_type", "RSA2")));
    }
}
