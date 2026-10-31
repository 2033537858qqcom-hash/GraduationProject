package cn.zzuit.payservice;

import java.util.Map;

/** 支付宝网关抽象，便于测试替换。 */
public interface AlipayGateway {
    record Query(String tradeStatus, String tradeNo, String totalAmount) {}
    record RefundResult(String orderNo,String requestNo,String amount,boolean confirmed) {}
    String precreate(String orderNo, String amount, String subject, int ttlMinutes) throws Exception;
    Query query(String orderNo) throws Exception;
    void close(String orderNo) throws Exception;
    RefundResult refund(String orderNo,String requestNo,String amount,String reason) throws Exception;
    boolean verify(Map<String, String> params);
    String appId();
}
