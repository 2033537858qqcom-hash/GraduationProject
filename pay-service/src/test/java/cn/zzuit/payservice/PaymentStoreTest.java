package cn.zzuit.payservice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
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
class PaymentStoreTest {
    @TestConfiguration static class Cfg { @Bean @Primary FakeGateway fake() { return new FakeGateway(); } }
    @Autowired PaymentStore store;
    @Autowired FakeGateway fake;
    @Autowired JdbcClient jdbc;

    @BeforeEach void clean() { jdbc.sql("DELETE FROM pay_order").update(); fake.next = null; fake.precreates = 0; fake.valid = true; }

    Map<String, String> notify(String order, String amount, String status) {
        var m = new HashMap<String, String>();
        m.put("app_id", "app1"); m.put("out_trade_no", order); m.put("total_amount", amount);
        m.put("trade_status", status); m.put("trade_no", "T" + order); m.put("sign", "ok"); m.put("sign_type", "RSA2");
        return m;
    }

    @Test void createIsIdempotentAndAmountImmutable() {
        var a = store.create("ORDER0001", "12.5", "测试");
        var b = store.create("ORDER0001", "12.50", "测试");
        assertEquals("12.50", a.amount());
        assertEquals(a.qrCode(), b.qrCode());
        assertEquals(1, fake.precreates);
        assertThrows(ResponseStatusException.class, () -> store.create("ORDER0001", "13.00", "测试"));
    }

    @Test void rejectsAmountMismatchAndBadSignatureAndAppId() {
        store.create("ORDER0002", "10.00", "x");
        assertFalse(store.notification(notify("ORDER0002", "0.01", "TRADE_SUCCESS")));
        var wrongApp = notify("ORDER0002", "10.00", "TRADE_SUCCESS"); wrongApp.put("app_id", "evil");
        assertFalse(store.notification(wrongApp));
        var badSign = notify("ORDER0002", "10.00", "TRADE_SUCCESS"); badSign.put("sign", "bad");
        assertFalse(store.notification(badSign));
        assertFalse(store.notification(notify("UNKNOWN01", "10.00", "TRADE_SUCCESS")));
        assertEquals("PENDING", store.find("ORDER0002").get().status());
    }

    @Test void duplicateNotifyIsIdempotent() {
        store.create("ORDER0003", "1.00", "x");
        assertTrue(store.notification(notify("ORDER0003", "1.00", "TRADE_SUCCESS")));
        assertTrue(store.notification(notify("ORDER0003", "1.00", "TRADE_SUCCESS")));
        assertTrue(store.notification(notify("ORDER0003", "1.00", "TRADE_FINISHED")));
        var other = notify("ORDER0003", "1.00", "TRADE_SUCCESS"); other.put("trade_no", "OTHER");
        assertFalse(store.notification(other));
        var o = store.find("ORDER0003").get();
        assertEquals("PAID", o.status()); assertEquals("TORDER0003", o.tradeNo());
    }

    @Test void pendingStatusActivelyQueries() {
        store.create("ORDER0004", "2.00", "x");
        fake.next = new AlipayGateway.Query("TRADE_SUCCESS", "TQ", "2.00");
        assertEquals("PAID", store.status("ORDER0004").get().status());
    }

    @Test void queryWithWrongAmountDoesNotPay() {
        store.create("ORDER0005", "2.00", "x");
        fake.next = new AlipayGateway.Query("TRADE_SUCCESS", "TQ", "0.01");
        assertEquals("PENDING", store.status("ORDER0005").get().status());
    }
}
