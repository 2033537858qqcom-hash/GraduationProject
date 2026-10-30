package cn.zzuit.payservice;

import static org.junit.jupiter.api.Assertions.*;

import com.alipay.api.internal.util.AlipaySignature;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class SignatureVerifyTest {
    @Test void rsa2VerifyAcceptsGenuineAndRejectsTampered() throws Exception {
        var g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        var kp = g.generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        var p = new HashMap<String, String>();
        p.put("app_id", "app1"); p.put("out_trade_no", "ORDER1"); p.put("total_amount", "1.00"); p.put("trade_status", "TRADE_SUCCESS");
        String content = AlipaySignature.getSignCheckContentV1(p);
        p.put("sign", AlipaySignature.rsa256Sign(content, priv, "UTF-8"));
        p.put("sign_type", "RSA2");
        var gw = new SdkAlipayGateway("https://example.invalid", "app1", priv, pub, "");
        assertTrue(gw.verify(new HashMap<>(p)));
        p.put("total_amount", "0.01");
        assertFalse(gw.verify(new HashMap<>(p)));
        p.put("total_amount", "1.00"); p.put("sign_type", "RSA");
        assertFalse(gw.verify(new HashMap<>(p)));
    }
}
