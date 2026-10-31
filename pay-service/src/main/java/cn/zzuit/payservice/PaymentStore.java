package cn.zzuit.payservice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentStore {
    private static final Logger log = LoggerFactory.getLogger(PaymentStore.class);
    private static final Set<String> PAID_STATES = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");
    public record Order(String orderNo, String amount, String subject, String status, String qrCode, String tradeNo, Instant expiresAt) {}
    public record Refund(String orderNo,String requestNo,String amount,String status) {}

    private final JdbcClient jdbc;
    private final AlipayGateway alipay;
    private final TransactionTemplate tx;
    private final int ttl;

    public PaymentStore(JdbcClient jdbc, AlipayGateway alipay, TransactionTemplate tx, @Value("${pay.order-ttl-minutes}") int ttl) {
        this.jdbc = jdbc; this.alipay = alipay; this.tx = tx; this.ttl = ttl;
    }

    private Order row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Order(r.getString("order_no"), r.getString("amount"), r.getString("subject"), r.getString("status"),
            r.getString("qr_code"), r.getString("trade_no"), r.getTimestamp("expires_at").toInstant());
    }

    public Optional<Order> find(String orderNo) {
        return jdbc.sql("SELECT * FROM pay_order WHERE order_no=:o").param("o", orderNo).query(this::row).optional();
    }

    static String normalize(String amount) {
        try {
            var v = new BigDecimal(amount);
            if (v.scale() > 2 || v.signum() <= 0 || v.compareTo(new BigDecimal("1000000")) > 0) throw new NumberFormatException();
            return v.setScale(2).toPlainString();
        } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额无效"); }
    }

    /** 创建订单：同一 orderNo 幂等，金额不一致直接拒绝。 */
    public synchronized Order create(String orderNo, String amount, String subject) {
        if (orderNo == null || !orderNo.matches("[A-Za-z0-9_-]{6,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单号无效");
        if (subject == null || subject.isBlank() || subject.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题无效");
        String normalized = normalize(amount);
        var existing = find(orderNo);
        if (existing.isPresent()) {
            var o = existing.get();
            if (!o.amount().equals(normalized)) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一订单号金额不可变更");
            if (!"PENDING".equals(o.status()) || o.qrCode() != null) return o;
        } else {
            var now = Instant.now();
            jdbc.sql("INSERT INTO pay_order(order_no,amount,subject,status,created_at,expires_at) VALUES(:o,:a,:s,'PENDING',:c,:e)")
                .param("o", orderNo).param("a", normalized).param("s", subject.trim())
                .param("c", Timestamp.from(now)).param("e", Timestamp.from(now.plus(ttl, ChronoUnit.MINUTES))).update();
        }
        try {
            String qr = alipay.precreate(orderNo, normalized, subject.trim(), ttl);
            jdbc.sql("UPDATE pay_order SET qr_code=:q WHERE order_no=:o").param("q", qr).param("o", orderNo).update();
        } catch (Exception e) {
            log.warn("precreate failed for {}: {}", orderNo, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝预下单失败");
        }
        return find(orderNo).orElseThrow();
    }

    /** Serializes against precreate so a closed order cannot later acquire a fresh QR code. */
    public synchronized Order closeOrder(String orderNo) {
        var current=status(orderNo).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"订单不存在"));
        if(!"PENDING".equals(current.status())) return current;
        try { alipay.close(orderNo); }
        catch(Exception failure) {
            var latest=status(orderNo).orElseThrow();
            if("PAID".equals(latest.status())) return latest;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"平台关单结果尚未确认");
        }
        markClosed(orderNo);return find(orderNo).orElseThrow();
    }
    /** A tombstone also prevents a previously timed-out, never-created order from being recreated. */
    public synchronized Order closeOrder(String orderNo,String amount,String subject) {
        if(orderNo==null || !orderNo.matches("[A-Za-z0-9_-]{6,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"订单号无效");
        String normalized=normalize(amount);var current=find(orderNo);
        if(current.isPresent() && !current.get().amount().equals(normalized)) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单金额不一致");
        if(current.isEmpty()) {
            jdbc.sql("INSERT INTO pay_order(order_no,amount,subject,status,created_at,expires_at) VALUES(:o,:a,:s,'PENDING',CURRENT_TIMESTAMP,:expires)")
                .param("o",orderNo).param("a",normalized).param("s",subject==null?"已撤销的校园账单":subject.substring(0,Math.min(128,subject.length())))
                .param("expires",Timestamp.from(Instant.now().plus(ttl,ChronoUnit.MINUTES))).update();
        }
        return closeOrder(orderNo);
    }
    private Refund refundRow(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Refund(r.getString("order_no"),r.getString("request_no"),r.getString("amount"),r.getString("status"));
    }
    public synchronized Refund refund(String orderNo,String requestNo,String amount,String reason) {
        if(requestNo==null || !requestNo.matches("[A-Za-z0-9-]{8,80}") || reason==null || reason.trim().length()<5 || reason.trim().length()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"退款请求标识或原因无效");
        String normalized=normalize(amount),description=reason.trim();
        var reserved=tx.execute(ignored->{
            jdbc.sql("SELECT order_no FROM pay_order WHERE order_no=:o FOR UPDATE").param("o",orderNo).query(String.class).optional()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"订单不存在"));
            var order=find(orderNo).orElseThrow();
            if(!order.status().equals("PAID")) throw new ResponseStatusException(HttpStatus.CONFLICT,"仅已确认收款的订单可以退款");
            var prior=jdbc.sql("SELECT * FROM pay_refund WHERE request_no=:r").param("r",requestNo).query((r,n)->new String[]{r.getString("order_no"),r.getString("amount"),r.getString("reason"),r.getString("status")}).optional();
            if(prior.isPresent()) {
                var p=prior.get();if(!p[0].equals(orderNo) || !p[1].equals(normalized) || !p[2].equals(description)) throw new ResponseStatusException(HttpStatus.CONFLICT,"退款请求标识已用于其他订单、金额或原因");
                return new Refund(orderNo,requestNo,normalized,p[3]);
            }
            var total=jdbc.sql("SELECT amount FROM pay_refund WHERE order_no=:o").param("o",orderNo).query(String.class).list().stream().map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(new BigDecimal(normalized).compareTo(new BigDecimal(order.amount()))!=0 || total.add(new BigDecimal(normalized)).compareTo(new BigDecimal(order.amount()))>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"仅支持整单退款，累计及待核实退款不得超过实付金额");
            jdbc.sql("INSERT INTO pay_refund(request_no,order_no,amount,reason,status) VALUES(:r,:o,:a,:reason,'PENDING')")
                .param("r",requestNo).param("o",orderNo).param("a",normalized).param("reason",description).update();
            return new Refund(orderNo,requestNo,normalized,"PENDING");
        });
        if(reserved.status().equals("REFUNDED")) return reserved;
        try {
            var result=alipay.refund(orderNo,requestNo,normalized,description);
            if(result==null || !result.confirmed() || !orderNo.equals(result.orderNo()) || !requestNo.equals(result.requestNo()) || result.amount()==null || new BigDecimal(result.amount()).compareTo(new BigDecimal(normalized))!=0)
                throw new IllegalStateException("Refund not confirmed");
            jdbc.sql("UPDATE pay_refund SET status='REFUNDED',last_error='',refunded_at=CURRENT_TIMESTAMP WHERE request_no=:r").param("r",requestNo).update();
        } catch(Exception failure) {
            log.warn("Refund unconfirmed: requestNo={}, failureType={}",requestNo,failure.getClass().getSimpleName());
            jdbc.sql("UPDATE pay_refund SET last_error='平台退款结果尚未确认，请使用原退款请求核对' WHERE request_no=:r").param("r",requestNo).update();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"平台退款结果尚未确认，请使用原退款请求重试");
        }
        return jdbc.sql("SELECT * FROM pay_refund WHERE request_no=:r").param("r",requestNo).query(this::refundRow).single();
    }

    /** 查询状态：PENDING 时主动向支付宝查询。 */
    public Optional<Order> status(String orderNo) {
        var found = find(orderNo);
        if (found.isEmpty() || !"PENDING".equals(found.get().status())) return found;
        var o = found.get();
        try {
            var q = alipay.query(orderNo);
            if (q != null && PAID_STATES.contains(q.tradeStatus()) && q.totalAmount() != null
                && new BigDecimal(q.totalAmount()).compareTo(new BigDecimal(o.amount())) == 0) {
                markPaid(orderNo, q.tradeNo());
            } else if (q != null && "TRADE_CLOSED".equals(q.tradeStatus())) {
                markClosed(orderNo);
            }
        } catch (Exception e) { log.warn("query failed for {}: {}", orderNo, e.getMessage()); }
        if (o.expiresAt().isBefore(Instant.now()) && "PENDING".equals(find(orderNo).get().status())) expire(o);
        return find(orderNo);
    }

    boolean markPaid(String orderNo, String tradeNo) {
        Integer n = tx.execute(s -> jdbc.sql("UPDATE pay_order SET status='PAID',trade_no=:t,paid_at=:p WHERE order_no=:o AND status IN ('PENDING','CLOSED')")
            .param("t", tradeNo).param("p", Timestamp.from(Instant.now())).param("o", orderNo).update());
        return n != null && n == 1;
    }

    void markClosed(String orderNo) {
        jdbc.sql("UPDATE pay_order SET status='CLOSED' WHERE order_no=:o AND status='PENDING'").param("o", orderNo).update();
    }

    private void expire(Order o) {
        try { alipay.close(o.orderNo()); } catch (Exception e) { log.debug("close failed: {}", e.getMessage()); }
        markClosed(o.orderNo());
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void expireOverdue() {
        jdbc.sql("SELECT * FROM pay_order WHERE status='PENDING' AND expires_at<:n").param("n", Timestamp.from(Instant.now()))
            .query(this::row).list().forEach(o -> status(o.orderNo()));
        jdbc.sql("DELETE FROM used_nonce WHERE seen_at<:t").param("t", Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES))).update();
    }

    /** 异步通知处理；返回 true 表示已在事务提交后确认，可回复 success。 */
    public boolean notification(Map<String, String> p) {
        if (!alipay.verify(p)) return false;
        if (!alipay.appId().equals(p.get("app_id"))) return false;
        var found = find(p.getOrDefault("out_trade_no", ""));
        if (found.isEmpty()) return false;
        var o = found.get();
        try {
            if (new BigDecimal(p.getOrDefault("total_amount", "x")).compareTo(new BigDecimal(o.amount())) != 0) return false;
        } catch (NumberFormatException e) { return false; }
        String status = p.getOrDefault("trade_status", "");
        String tradeNo = p.getOrDefault("trade_no", "");
        if ("WAIT_BUYER_PAY".equals(status)) return true;
        if ("TRADE_CLOSED".equals(status)) { markClosed(o.orderNo()); return true; }
        if (!PAID_STATES.contains(status) || tradeNo.isBlank()) return false;
        if ("PAID".equals(o.status())) return tradeNo.equals(o.tradeNo());
        markPaid(o.orderNo(), tradeNo); // 事务内条件更新，提交后返回
        return tradeNo.equals(find(o.orderNo()).get().tradeNo());
    }
}
