package cn.zzuit.payservice;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SdkAlipayGateway implements AlipayGateway {
    private final String gateway, appId, privateKey, publicKey, notifyUrl;
    private volatile AlipayClient client;

    public SdkAlipayGateway(@Value("${pay.alipay.gateway}") String gateway, @Value("${pay.alipay.app-id}") String appId,
                            @Value("${pay.alipay.private-key}") String privateKey, @Value("${pay.alipay.alipay-public-key}") String publicKey,
                            @Value("${pay.alipay.notify-url}") String notifyUrl) {
        this.gateway = gateway; this.appId = appId; this.privateKey = privateKey; this.publicKey = publicKey; this.notifyUrl = notifyUrl;
    }

    private AlipayClient client() {
        if (appId.isBlank() || privateKey.isBlank() || publicKey.isBlank()) throw new IllegalStateException("支付宝未配置");
        if (client == null) client = new DefaultAlipayClient(gateway, appId, privateKey, "json", "UTF-8", publicKey, "RSA2");
        return client;
    }

    static String json(Map<String, String> m) {
        try { return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(m); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid payment JSON",invalid); }
    }

    @Override public String precreate(String orderNo, String amount, String subject, int ttl) throws Exception {
        var req = new AlipayTradePrecreateRequest();
        if (!notifyUrl.isBlank()) req.setNotifyUrl(notifyUrl);
        req.setBizContent(json(Map.of("out_trade_no", orderNo, "total_amount", amount, "subject", subject, "timeout_express", ttl + "m")));
        var resp = client().execute(req);
        if (!resp.isSuccess() || resp.getQrCode() == null) throw new IllegalStateException("预下单失败: " + resp.getCode() + " " + resp.getSubCode());
        return resp.getQrCode();
    }

    @Override public Query query(String orderNo) throws Exception {
        var req = new AlipayTradeQueryRequest();
        req.setBizContent(json(Map.of("out_trade_no", orderNo)));
        var resp = client().execute(req);
        if (!resp.isSuccess()) return null; // 例如 ACQ.TRADE_NOT_EXIST：用户尚未扫码
        return new Query(resp.getTradeStatus(), resp.getTradeNo(), resp.getTotalAmount());
    }

    @Override public void close(String orderNo) throws Exception {
        var req = new AlipayTradeCloseRequest();
        req.setBizContent(json(Map.of("out_trade_no", orderNo)));
        var response=client().execute(req);
        if(!response.isSuccess() && !"ACQ.TRADE_NOT_EXIST".equals(response.getSubCode()))
            throw new IllegalStateException("关单未确认: "+response.getCode()+" "+response.getSubCode());
    }

    @Override public RefundResult refund(String orderNo,String requestNo,String amount,String reason) throws Exception {
        var request=new AlipayTradeRefundRequest();request.setBizContent(json(Map.of("out_trade_no",orderNo,"out_request_no",requestNo,"refund_amount",amount,"refund_reason",reason)));
        var response=client().execute(request);
        return new RefundResult(response.getOutTradeNo(),requestNo,response.getRefundFee(),response.isSuccess());
    }

    @Override public boolean verify(Map<String, String> params) {
        try {
            return "RSA2".equals(params.get("sign_type")) && AlipaySignature.rsaCheckV1(new HashMap<>(params), publicKey, "UTF-8", "RSA2");
        } catch (Exception e) { return false; }
    }

    @Override public String appId() { return appId; }
}
