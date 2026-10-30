package cn.zzuit.campus.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public class PaymentService {
    static boolean matches(Map<String,String> params, String app, String seller, String order, long cents) {
        try {
            return app.equals(params.get("app_id")) && seller.equals(params.get("seller_id"))
                && order.equals(params.get("out_trade_no")) && params.getOrDefault("trade_no", "").length() > 0
                && Set.of("TRADE_SUCCESS", "TRADE_FINISHED").contains(params.getOrDefault("trade_status", ""))
                && new BigDecimal(params.get("total_amount")).compareTo(BigDecimal.valueOf(cents, 2)) == 0;
        } catch (NumberFormatException | NullPointerException ex) { return false; }
    }
}
