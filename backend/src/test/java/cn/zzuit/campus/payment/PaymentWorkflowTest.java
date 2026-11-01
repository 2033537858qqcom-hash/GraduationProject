package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import com.alipay.api.internal.util.AlipaySignature;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "app.demo.enabled=true", "app.cos.enabled=false", "app.repair.reminders.enabled=false"})
class PaymentWorkflowTest {
    static final KeyPair KEYS = keys();
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("app.payment.enabled", () -> "true");
        r.add("app.payment.channel", () -> "ijpay");
        r.add("app.payment.followup.enabled", () -> "false");
        r.add("app.payment.app-id", () -> "test-app");
        r.add("app.payment.seller-id", () -> "test-seller");
        r.add("app.payment.private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        r.add("app.payment.alipay-public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        r.add("app.payment.notify-url", () -> "https://example.invalid/notify");
        r.add("app.payment.return-url", () -> "http://localhost/portal/payments");
    }
    @Autowired PaymentService service;
    @Autowired PaymentManagementService finance;
    @Autowired IdentityService identities;

    @Test void billBelongsToStudentAndRepeatedCreationUsesSameOrder() {
        var admin = user("finance1001"); var student = user("20260001"); var other = user("20260002");
        String key = UUID.randomUUID().toString();
        var draft = finance.create(admin, input(key,"演示费用",1250));
        assertThat(finance.create(admin, input(key,"演示费用",1250)).id()).isEqualTo(draft.id());
        assertThatThrownBy(() -> finance.create(admin, input(key,"不同费用",1250))).isInstanceOf(ApiException.class);
        var bill = publish(draft);
        assertThat(service.mine(student)).extracting(PaymentService.Bill::id).contains(bill.id());
        assertThat(service.mine(other)).extracting(PaymentService.Bill::id).doesNotContain(bill.id());
        assertThatThrownBy(() -> service.pay(other, bill.id())).isInstanceOf(ApiException.class);
        assertThat(service.pay(student, bill.id())).contains(bill.orderNo(), "alipay.trade.page.pay");
        assertThat(service.pay(student, bill.id())).contains(bill.orderNo());
    }

    @Test void callbackMustHaveValidSignatureAndMatchingFieldsAndIsIdempotent() throws Exception {
        var student = user("20260001");
        var bill = publish(finance.create(user("finance1001"),input(UUID.randomUUID().toString(),"演示回调",1234)));
        service.pay(student,bill.id());
        var values = new HashMap<>(Map.of("app_id", "test-app", "seller_id", "test-seller", "out_trade_no", bill.orderNo(),
            "trade_no", "external-" + UUID.randomUUID(), "total_amount", "12.34", "trade_status", "TRADE_SUCCESS"));
        String signature = AlipaySignature.rsaSign(AlipaySignature.getSignContent(values), Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()), "UTF-8", "RSA2");
        values.put("sign", signature); values.put("sign_type", "RSA2");
        var tampered = new HashMap<>(values); tampered.put("total_amount", "123.40");
        assertThat(service.notification(tampered)).isFalse();
        assertThat(service.mine(student).stream().filter(b -> b.id() == bill.id()).findFirst().orElseThrow().status()).isEqualTo("UNPAID");
        assertThat(service.notification(values)).isTrue();
        assertThat(service.notification(values)).isTrue();
        assertThat(service.mine(student).stream().filter(b -> b.id() == bill.id()).findFirst().orElseThrow().status()).isEqualTo("PAID");
        assertThatThrownBy(() -> service.pay(student, bill.id())).isInstanceOf(ApiException.class);
    }

    private CampusPrincipal user(String name) { return (CampusPrincipal) identities.loadUserByUsername(name); }
    private FinanceModels.BillInput input(String key,String title,long amount) {
        return new FinanceModels.BillInput(key,"20260001","OTHER",title,"独立测试账期",java.time.LocalDate.now().plusDays(10),null,"1",amount,"独立支付回归测试的明确收费依据",null,null,null);
    }
    private PaymentService.Bill publish(PaymentService.Bill bill) {
        var submitted=finance.action(user("finance1001"),bill.id(),new FinanceModels.Action("SUBMIT",bill.version(),UUID.randomUUID().toString(),"已核对测试账单依据",null));
        return finance.action(user("finance2001"),bill.id(),new FinanceModels.Action("APPROVE",submitted.version(),UUID.randomUUID().toString(),"独立复核测试账单依据",null));
    }
    private static KeyPair keys() { try { var g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch(Exception e) { throw new IllegalStateException(e); } }
}
