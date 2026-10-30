package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentNotificationTest {
    @Test void acceptsOnlyMatchingSuccessfulPayment() {
        var notification = valid();
        assertThat(PaymentService.matches(notification, "app", "seller", "order", 1234)).isTrue();
        for (var field : Map.of("app_id", "other", "seller_id", "other", "out_trade_no", "other",
                "total_amount", "12.35", "trade_status", "WAIT_BUYER_PAY", "trade_no", "").entrySet()) {
            var altered = new HashMap<>(notification);
            altered.put(field.getKey(), field.getValue());
            assertThat(PaymentService.matches(altered, "app", "seller", "order", 1234)).isFalse();
        }
    }
    @Test void rejectsMalformedAmountAndAcceptsFinishedStatus() {
        var notification = valid();
        notification.put("total_amount", "invalid");
        assertThat(PaymentService.matches(notification, "app", "seller", "order", 1234)).isFalse();
        notification.put("total_amount", "12.34");
        notification.put("trade_status", "TRADE_FINISHED");
        assertThat(PaymentService.matches(notification, "app", "seller", "order", 1234)).isTrue();
    }
    private HashMap<String,String> valid() {
        return new HashMap<>(Map.of("app_id", "app", "seller_id", "seller", "out_trade_no", "order",
            "total_amount", "12.34", "trade_status", "TRADE_SUCCESS", "trade_no", "platform-order"));
    }
}
