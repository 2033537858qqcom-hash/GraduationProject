package cn.zzuit.campus.payment;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.ijpay.alipay.AliPayApi;
import com.ijpay.alipay.AliPayApiConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Verified gateway facts control settlement; independent finance review controls publication. */
@Service
public class PaymentService {
    private static final Logger log=LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository repository;
    private final IdentityService identities;
    private final Environment env;
    private final PaymentEvents events;
    private final TransactionTemplate tx;
    public PaymentService(PaymentRepository repository,IdentityService identities,Environment env,PaymentEvents events,TransactionTemplate tx) {
        this.repository=repository;this.identities=identities;this.env=env;this.events=events;this.tx=tx;
    }
    public record Category(String code,String name,String description) {}
    public record Bill(long id,String orderNo,long studentId,String title,String categoryCode,String categoryName,long amountCents,String status,String tradeNo,
        String workflowStatus,String billingPeriod,LocalDate dueDate,String basis,Long standardId,String standardName,Long unitPriceCents,String quantity,
        Long sourceBasisId,long createdBy,String creatorName,String studentUsername,String studentName,Long campusId,LocalDateTime createdAt,LocalDateTime publishedAt,LocalDateTime paidAt,
        String refundStatus,Long pendingRequestId,LocalDateTime paymentStartedAt,int version,String lastSyncError,LocalDateTime lastCheckedAt) {}
    private record StartResult(Map<String,String> value,ApiException error) {}
    static ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
    void requireDemo() {
        if(!env.getProperty("app.demo.enabled",Boolean.class,false)) throw error(HttpStatus.FORBIDDEN,"DEMO_DISABLED","演示缴费未开放");
    }
    public List<Category> categories(CampusPrincipal user) {
        String permission=user.permissions().contains("payment:manage")?"payment:manage":user.permissions().contains("payment:review")?"payment:review":
            user.permissions().contains("payment:source")?"payment:source":"portal:read";
        identities.requirePermission(user,permission);requireDemo();return catalog();
    }
    List<Category> catalog() {
        return repository.jdbc.sql("SELECT code,name,description FROM payment_category ORDER BY sort_order,code")
            .query((r,n)->new Category(r.getString(1),r.getString(2),r.getString(3))).list();
    }
    public List<Bill> mine(CampusPrincipal user) {
        identities.requirePermission(user,"portal:read");requireDemo();
        return repository.jdbc.sql(PaymentRepository.BILL_QUERY+" WHERE b.student_id=:id AND b.published_at IS NOT NULL ORDER BY b.id DESC")
            .param("id",user.id()).query(repository::bill).list();
    }
    Bill owned(CampusPrincipal user,long id) {
        identities.requirePermission(user,"portal:read");requireDemo();var bill=repository.load(id);
        if(bill.studentId()!=user.id() || bill.publishedAt()==null) throw error(HttpStatus.NOT_FOUND,"BILL_NOT_FOUND","账单不存在");
        return bill;
    }
    private String setting(String key) {
        String value=env.getProperty("app.payment."+key,"").trim();
        if(value.isEmpty()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_UNAVAILABLE","支付渠道尚未配置");return value;
    }
    boolean enabled() { return env.getProperty("app.payment.enabled",Boolean.class,false); }
    boolean remote() { return "remote".equalsIgnoreCase(env.getProperty("app.payment.channel","ijpay").trim()); }
    RemotePayClient remoteClient() { return new RemotePayClient(setting("remote-url"),setting("remote-token")); }
    private void available() { requireDemo();if(!enabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_UNAVAILABLE","支付宝沙箱尚未配置"); }
    public Map<String,String> start(CampusPrincipal user,long id) {
        owned(user,id);
        var result=tx.execute(ignored->{
            var bill=repository.lock(id);
            if(!"PUBLISHED".equals(bill.workflowStatus()) || bill.pendingRequestId()!=null || !bill.status().equals("UNPAID"))
                throw error(HttpStatus.CONFLICT,"BILL_NOT_PAYABLE","账单当前不可支付，请刷新账单状态");
            available();
            repository.jdbc.sql("UPDATE payment_bill SET payment_started_at=COALESCE(payment_started_at,CURRENT_TIMESTAMP) WHERE id=:id").param("id",id).update();
            try {
                if(!remote()) return new StartResult(Map.of("url",pagePay(bill)),null);
                var order=remoteClient().create(bill.orderNo(),BigDecimal.valueOf(bill.amountCents(),2).toPlainString(),"校园缴费："+bill.title());
                verifyOrder(bill,order);
                if("PAID".equals(order.status())) { markPaid(bill,order.tradeNo());return new StartResult(null,error(HttpStatus.CONFLICT,"BILL_PAID","账单已支付，请刷新查看")); }
                if(!"PENDING".equals(order.status()) || order.qrCode()==null || order.qrCode().isBlank())
                    return new StartResult(null,error(HttpStatus.CONFLICT,"PAYMENT_CLOSED","支付订单已关闭，请联系财务人员处理"));
                return new StartResult(Map.of("qrCode",order.qrCode()),null);
            } catch(Exception failure) {
                log.warn("Payment start unconfirmed: billId={}, failureType={}",id,failure.getClass().getSimpleName());
                return new StartResult(null,error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_UNAVAILABLE","暂时无法确认下单结果，请重试原账单或联系财务查单"));
            }
        });
        if(result.error()!=null) throw result.error();return result.value();
    }
    /** Compatibility for the in-process sandbox integration. */
    public String pay(CampusPrincipal user,long id) { return start(user,id).get("url"); }
    public Bill status(CampusPrincipal user,long id) { owned(user,id);return synchronize(id,false); }
    Bill synchronize(long id,boolean failOnError) {
        var result=tx.execute(ignored->{
            var bill=repository.lock(id);
            if(!enabled() || !remote() || !bill.status().equals("UNPAID") || bill.paymentStartedAt()==null) return bill;
            try {
                var order=remoteClient().status(bill.orderNo());verifyOrder(bill,order);
                if("PAID".equals(order.status())) markPaid(bill,order.tradeNo());
                repository.jdbc.sql("UPDATE payment_bill SET last_sync_error='',last_checked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).update();
            } catch(Exception failure) {
                log.warn("Payment status unconfirmed: billId={}, failureType={}",id,failure.getClass().getSimpleName());
                repository.jdbc.sql("UPDATE payment_bill SET last_sync_error='暂时无法核验支付平台状态，请稍后重试或联系财务查单',last_checked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).update();
            }
            return repository.load(id);
        });
        if(failOnError && !result.lastSyncError().isEmpty()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_SYNC_FAILED",result.lastSyncError());
        return result;
    }
    private void verifyOrder(Bill bill,RemotePayClient.Order order) {
        if(!bill.orderNo().equals(order.orderNo()) || order.amount()==null || new BigDecimal(order.amount()).compareTo(BigDecimal.valueOf(bill.amountCents(),2))!=0)
            throw new IllegalStateException("Payment order mismatch");
        if("PAID".equals(order.status()) && (order.tradeNo()==null || order.tradeNo().isBlank())) throw new IllegalStateException("Missing trade number");
    }
    private void markPaid(Bill bill,String tradeNo) {
        if(tradeNo==null || tradeNo.isBlank()) throw new IllegalArgumentException("Missing trade number");
        int changed=repository.jdbc.sql("UPDATE payment_bill SET status='PAID',trade_no=:trade,paid_at=CURRENT_TIMESTAMP,last_sync_error='',version=version+1 WHERE id=:id AND status='UNPAID'")
            .param("trade",tradeNo).param("id",bill.id()).update();
        if(changed==1) {
            events.history(null,"BILL",bill.id(),"SETTLE",null,null,"支付平台已确认金额和交易号",repository.load(bill.id()));
            events.student(bill.studentId(),"缴费结果已确认",bill.id(),bill.title()+"已支付，可查看缴费凭证。");
            if(!bill.workflowStatus().equals("PUBLISHED")) events.finance("payment:manage",0,"异常收款待核对","PAYMENT",bill.id(),"非正常收费状态下确认收款，请核对并按退款流程处理。");
        }
    }
    void close(Bill bill) {
        if(bill.paymentStartedAt()==null) return;
        available();
        if(!remote()) throw error(HttpStatus.CONFLICT,"PAYMENT_CLOSE_REQUIRED","该网页支付订单已发起，请先核实平台状态；不能直接作废或减免");
        var closed=remoteClient().close(bill.orderNo(),BigDecimal.valueOf(bill.amountCents(),2).toPlainString(),bill.title());verifyOrder(bill,closed);
        if("PAID".equals(closed.status())) throw error(HttpStatus.CONFLICT,"BILL_PAID","平台已收款，请先查单，再申请退款");
        if(!"CLOSED".equals(closed.status())) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_CLOSE_UNCONFIRMED","平台关单尚未确认，请稍后重试");
    }
    void refund(Bill bill,String requestNo,String reason) {
        available();String amount=BigDecimal.valueOf(bill.amountCents(),2).toPlainString();
        if(remote()) {
            var result=remoteClient().refund(bill.orderNo(),requestNo,amount,reason);
            if(!bill.orderNo().equals(result.orderNo()) || !requestNo.equals(result.requestNo()) || !"REFUNDED".equals(result.status()) ||
                result.amount()==null || new BigDecimal(result.amount()).compareTo(new BigDecimal(amount))!=0)
                throw error(HttpStatus.SERVICE_UNAVAILABLE,"REFUND_UNCONFIRMED","平台退款尚未确认，请保留原请求重试");
            return;
        }
        try {
            var request=new AlipayTradeRefundRequest();request.setBizContent(JsonMapper.builder().build().writeValueAsString(
                Map.of("out_trade_no",bill.orderNo(),"out_request_no",requestNo,"refund_amount",amount,"refund_reason",reason)));
            var response=alipayClient().execute(request);
            if(!response.isSuccess() || !bill.orderNo().equals(response.getOutTradeNo()) || response.getRefundFee()==null || new BigDecimal(response.getRefundFee()).compareTo(new BigDecimal(amount))!=0)
                throw new IllegalStateException("Refund unconfirmed");
        } catch(Exception failure) { throw error(HttpStatus.SERVICE_UNAVAILABLE,"REFUND_UNCONFIRMED","平台退款尚未确认，请保留原请求重试"); }
    }
    private com.alipay.api.AlipayClient alipayClient() {
        return AliPayApiConfig.builder().setAppId(setting("app-id")).setServiceUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do")
            .setPrivateKey(setting("private-key")).setAliPayPublicKey(setting("alipay-public-key")).setCharset("UTF-8").setSignType("RSA2").build().getAliPayClient();
    }
    private String pagePay(Bill bill) throws Exception {
        setting("seller-id");var model=new AlipayTradePagePayModel();model.setOutTradeNo(bill.orderNo());
        model.setTotalAmount(BigDecimal.valueOf(bill.amountCents(),2).toPlainString());model.setSubject("校园缴费："+bill.title());model.setProductCode("FAST_INSTANT_TRADE_PAY");
        var request=new AlipayTradePagePayRequest();request.setBizModel(model);request.setNotifyUrl(setting("notify-url"));request.setReturnUrl(setting("return-url"));
        return AliPayApi.pageExecute(alipayClient(),request,"GET").getBody();
    }
    public boolean notification(Map<String,String> params) {
        try {
            available();if(!"RSA2".equals(params.get("sign_type")) || !AlipaySignature.rsaCheckV1(new java.util.HashMap<>(params),setting("alipay-public-key"),"UTF-8","RSA2")) return false;
            return tx.execute(ignored->{
                var id=repository.jdbc.sql("SELECT id FROM payment_bill WHERE order_no=:order").param("order",params.get("out_trade_no")).query(Long.class).optional();
                if(id.isEmpty()) return false;var bill=repository.lock(id.get());
                if(!matches(params,setting("app-id"),setting("seller-id"),bill.orderNo(),bill.amountCents()) || bill.paymentStartedAt()==null) return false;
                if(bill.status().equals("PAID")) return params.get("trade_no").equals(bill.tradeNo());
                markPaid(bill,params.get("trade_no"));return true;
            });
        } catch(Exception failure) { log.warn("Rejected payment notification: failureType={}",failure.getClass().getSimpleName());return false; }
    }
    static boolean matches(Map<String,String> params,String app,String seller,String order,long cents) {
        try { return app.equals(params.get("app_id")) && seller.equals(params.get("seller_id")) && order.equals(params.get("out_trade_no")) &&
            !params.getOrDefault("trade_no","").isEmpty() && Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(params.getOrDefault("trade_status","")) &&
            new BigDecimal(params.get("total_amount")).compareTo(BigDecimal.valueOf(cents,2))==0;
        } catch(NumberFormatException | NullPointerException invalid) { return false; }
    }
}
