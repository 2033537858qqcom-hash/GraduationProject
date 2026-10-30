package cn.zzuit.campus.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.internal.util.AlipaySignature;
import com.ijpay.alipay.AliPayApi;
import com.ijpay.alipay.AliPayApiConfig;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final Environment env;
    public PaymentService(JdbcClient jdbc, IdentityService identities, Environment env) {
        this.jdbc=jdbc; this.identities=identities; this.env=env;
    }
    public record Bill(long id,String orderNo,long studentId,String title,long amountCents,String status,String tradeNo) {}
    private Bill row(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Bill(r.getLong("id"),r.getString("order_no"),r.getLong("student_id"),r.getString("title"),r.getLong("amount_cents"),r.getString("status"),r.getString("trade_no"));
    }
    private ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
    private void requireDemo() {
        if (!env.getProperty("app.demo.enabled",Boolean.class,false)) throw error(HttpStatus.FORBIDDEN,"DEMO_DISABLED","演示缴费未开放");
    }
    @Transactional
    public Bill create(CampusPrincipal user,String key,String username,String title,long cents) {
        identities.requirePermission(user,"foundation:manage"); requireDemo();
        if(key==null || !key.matches("[A-Za-z0-9-]{20,80}") || username==null || username.isBlank() || title==null || title.isBlank() || title.trim().length()>120 || cents<1 || cents>100000000L)
            throw error(HttpStatus.BAD_REQUEST,"INVALID_BILL","请检查账单信息");
        var student=jdbc.sql("SELECT u.id FROM app_user u JOIN user_role r ON r.user_id=u.id WHERE u.username=:name AND u.enabled=TRUE AND u.demo=TRUE AND r.role_code='STUDENT'")
            .param("name",username.trim()).query(Long.class).optional().orElseThrow(() -> error(HttpStatus.NOT_FOUND,"STUDENT_NOT_FOUND","未找到可用的演示学生"));
        var existing=jdbc.sql("SELECT * FROM payment_bill WHERE request_key=:key").param("key",key).query(this::row).optional();
        if(existing.isPresent()) {
            var bill=existing.get();
            if(bill.studentId()!=student || !bill.title().equals(title.trim()) || bill.amountCents()!=cents) throw error(HttpStatus.CONFLICT,"REQUEST_CONFLICT","请求标识已用于其他账单");
            return bill;
        }
        var id=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO payment_bill(request_key,order_no,student_id,created_by,title,amount_cents) VALUES(:key,:order,:student,:creator,:title,:cents)")
            .param("key",key).param("order",UUID.randomUUID().toString().replace("-", "")).param("student",student)
            .param("creator",user.id()).param("title",title.trim()).param("cents",cents).update(id,"id");
        return bill(id.getKey().longValue());
    }
    public List<Bill> mine(CampusPrincipal user) {
        identities.requirePermission(user,"portal:read"); requireDemo();
        return jdbc.sql("SELECT * FROM payment_bill WHERE student_id=:id ORDER BY id DESC LIMIT 200").param("id",user.id()).query(this::row).list();
    }
    private Bill bill(long id) {
        return jdbc.sql("SELECT * FROM payment_bill WHERE id=:id").param("id",id).query(this::row).optional()
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND,"BILL_NOT_FOUND","账单不存在"));
    }
    private String setting(String key) {
        String value=env.getProperty("app.payment."+key,"").trim();
        if(value.isEmpty()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_UNAVAILABLE","支付宝沙箱尚未配置");
        return value;
    }
    private void available() {
        requireDemo();
        if(!env.getProperty("app.payment.enabled",Boolean.class,false)) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_UNAVAILABLE","支付宝沙箱尚未配置");
    }
    public String pay(CampusPrincipal user,long id) {
        identities.requirePermission(user,"portal:read"); available();
        var bill=bill(id);
        if(bill.studentId()!=user.id()) throw error(HttpStatus.NOT_FOUND,"BILL_NOT_FOUND","账单不存在");
        if(!bill.status().equals("UNPAID")) throw error(HttpStatus.CONFLICT,"BILL_PAID","账单已支付");
        try {
            var client=AliPayApiConfig.builder().setAppId(setting("app-id")).setServiceUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do")
                .setPrivateKey(setting("private-key")).setAliPayPublicKey(setting("alipay-public-key")).setCharset("UTF-8").setSignType("RSA2").build().getAliPayClient();
            setting("seller-id");
            var model=new AlipayTradePagePayModel(); model.setOutTradeNo(bill.orderNo());
            model.setTotalAmount(BigDecimal.valueOf(bill.amountCents(),2).toPlainString()); model.setSubject("演示缴费："+bill.title()); model.setProductCode("FAST_INSTANT_TRADE_PAY");
            var request=new AlipayTradePagePayRequest(); request.setBizModel(model); request.setNotifyUrl(setting("notify-url")); request.setReturnUrl(setting("return-url"));
            return AliPayApi.pageExecute(client,request,"GET").getBody();
        } catch(ApiException ex) { throw ex; }
        catch(Exception ex) { throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_UNAVAILABLE","暂时无法发起沙箱支付"); }
    }
    @Transactional
    public boolean notification(Map<String,String> params) {
        try {
            available();
            if(!"RSA2".equals(params.get("sign_type")) || !AlipaySignature.rsaCheckV1(new java.util.HashMap<>(params),setting("alipay-public-key"),"UTF-8","RSA2")) return false;
            var found=jdbc.sql("SELECT * FROM payment_bill WHERE order_no=:order").param("order",params.get("out_trade_no")).query(this::row).optional();
            if(found.isEmpty()) return false;
            var bill=found.get();
            if(!matches(params,setting("app-id"),setting("seller-id"),bill.orderNo(),bill.amountCents())) return false;
            if(bill.status().equals("PAID")) return params.get("trade_no").equals(bill.tradeNo());
            int updated=jdbc.sql("UPDATE payment_bill SET status='PAID',trade_no=:trade,paid_at=CURRENT_TIMESTAMP WHERE id=:id AND status='UNPAID'")
                .param("trade",params.get("trade_no")).param("id",bill.id()).update();
            return updated==1 || params.get("trade_no").equals(bill(bill.id()).tradeNo());
        } catch(com.alipay.api.AlipayApiException | ApiException ex) { return false; }
    }
    static boolean matches(Map<String,String> params, String app, String seller, String order, long cents) {
        try {
            return app.equals(params.get("app_id")) && seller.equals(params.get("seller_id"))
                && order.equals(params.get("out_trade_no")) && params.getOrDefault("trade_no", "").length() > 0
                && Set.of("TRADE_SUCCESS", "TRADE_FINISHED").contains(params.getOrDefault("trade_status", ""))
                && new BigDecimal(params.get("total_amount")).compareTo(BigDecimal.valueOf(cents, 2)) == 0;
        } catch (NumberFormatException | NullPointerException ex) { return false; }
    }
}
