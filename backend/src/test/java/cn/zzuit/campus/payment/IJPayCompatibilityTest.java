package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.*;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.ijpay.alipay.AliPayApi;
import com.ijpay.alipay.AliPayApiConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Offline contracts for the pinned IJPay dependency and its transitive libraries. */
class IJPayCompatibilityTest {
    private static KeyPair applicationKeys;
    private static KeyPair platformKeys;

    @BeforeAll static void generateTestKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        applicationKeys = generator.generateKeyPair();
        platformKeys = generator.generateKeyPair();
    }

    @Test void pageFormUsesIJPayCoreWithoutPassingLegacyServletObjects() throws Exception {
        var form = page(client("offline-application", "https://example.invalid/gateway.do"), "OFFLINE_ORDER");
        assertThat(form).contains("alipay.trade.page.pay", "offline-application", "OFFLINE_ORDER", "1.00", "RSA2");
        assertThat(Class.forName("javax.servlet.http.HttpServletResponse"))
            .isNotEqualTo(jakarta.servlet.http.HttpServletResponse.class);
    }

    @Test void explicitClientsKeepApplicationConfigurationIsolatedAcrossThreads() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> page(client("offline-first", "https://example.invalid/gateway.do"), "FIRST"));
            var second = executor.submit(() -> page(client("offline-second", "https://example.invalid/gateway.do"), "SECOND"));
            assertThat(first.get()).contains("offline-first", "FIRST").doesNotContain("offline-second");
            assertThat(second.get()).contains("offline-second", "SECOND").doesNotContain("offline-first");
        }
    }

    @Test void notificationSignatureRejectsChangedAmountAndWrongPublicKey() throws Exception {
        var parameters = notification();
        assertThat(verify(parameters, platformKeys)).isTrue();
        assertThat(verify(parameters, applicationKeys)).isFalse();
        parameters.put("total_amount", "100.00");
        assertThat(verify(parameters, platformKeys)).isFalse();
    }

    @Test void notificationSignatureRejectsMalformedSignature() throws Exception {
        var parameters = notification();
        parameters.put("sign", "not-a-base64-signature");
        assertThatThrownBy(() -> verify(parameters, platformKeys)).isInstanceOf(AlipayApiException.class);
    }

    @Test void localGatewayQueryExercisesHttpJsonAndResponseSignatureLibraries() throws Exception {
        var capturedRequest = new AtomicReference<String>();
        var server = gateway(false, capturedRequest);
        try {
            var model = new AlipayTradeQueryModel();
            model.setOutTradeNo("OFFLINE_ORDER");
            var response = AliPayApi.tradeQueryToResponse(client("offline-application", url(server)), false, model);
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getOutTradeNo()).isEqualTo("OFFLINE_ORDER");
            assertThat(response.getTradeStatus()).isEqualTo("TRADE_SUCCESS");
            assertThat(response.getTotalAmount()).isEqualTo("1.00");
            assertThat(capturedRequest.get()).contains("alipay.trade.query", "app_id=offline-application", "sign=");
        } finally {
            server.stop(0);
        }
    }

    @Test void localGatewayResponseWithTamperedAmountFailsSignatureVerification() throws Exception {
        var server = gateway(true, new AtomicReference<>());
        try {
            var model = new AlipayTradeQueryModel();
            model.setOutTradeNo("OFFLINE_ORDER");
            assertThatThrownBy(() -> AliPayApi.tradeQueryToResponse(
                client("offline-application", url(server)), false, model)).isInstanceOf(AlipayApiException.class);
        } finally {
            server.stop(0);
        }
    }

    private static AlipayClient client(String appId, String gateway) {
        return AliPayApiConfig.builder().setAppId(appId).setServiceUrl(gateway)
            .setPrivateKey(privateKey(applicationKeys)).setAliPayPublicKey(publicKey(platformKeys))
            .setCharset("UTF-8").setSignType("RSA2").build().getAliPayClient();
    }

    private static String page(AlipayClient client, String orderNo) throws AlipayApiException {
        var model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderNo);
        model.setTotalAmount("1.00");
        model.setSubject("Offline compatibility check");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        var request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl("https://example.invalid/notify");
        request.setReturnUrl("https://example.invalid/return");
        // The tradePage convenience overloads reference javax.servlet even with non-Servlet arguments.
        return AliPayApi.pageExecute(client, request).getBody();
    }

    private static HashMap<String, String> notification() throws AlipayApiException {
        var parameters = new HashMap<>(Map.of("out_trade_no", "OFFLINE_ORDER", "total_amount", "1.00",
            "app_id", "offline-application", "seller_id", "offline-seller", "trade_status", "TRADE_SUCCESS"));
        parameters.put("sign", sign(AlipaySignature.getSignContent(parameters)));
        parameters.put("sign_type", "RSA2");
        return parameters;
    }

    private static boolean verify(Map<String, String> parameters, KeyPair pair) throws AlipayApiException {
        return AlipaySignature.rsaCheckV1(new HashMap<>(parameters), publicKey(pair), "UTF-8", "RSA2");
    }

    private static HttpServer gateway(boolean tamper, AtomicReference<String> captured) throws Exception {
        String content = "{\"code\":\"10000\",\"msg\":\"Success\",\"out_trade_no\":\"OFFLINE_ORDER\","
            + "\"trade_no\":\"OFFLINE_TRANSACTION\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"1.00\"}";
        String body = "{\"alipay_trade_query_response\":" + (tamper ? content.replace("1.00", "100.00") : content)
            + ",\"sign\":\"" + sign(content) + "\"}";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway.do", exchange -> {
            try {
                captured.set(exchange.getRequestURI().getRawQuery() + "&"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway.do";
    }

    private static String sign(String content) throws AlipayApiException {
        return AlipaySignature.rsaSign(content, privateKey(platformKeys), "UTF-8", "RSA2");
    }

    private static String privateKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }

    private static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }
}
