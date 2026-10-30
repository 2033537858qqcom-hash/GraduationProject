package cn.zzuit.campus.payment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 独立 pay-service 的客户端：请求使用 HMAC-SHA256 签名（method+path+timestamp+nonce+body）。 */
public class RemotePayClient {
    public record Order(String orderNo, String amount, String status, String qrCode, String tradeNo) {}
    private final String baseUrl, token;
    private final RestClient http;

    public RemotePayClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.token = token;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5)); factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public static String sign(String token, String method, String path, String ts, String nonce, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.join("\n", method, path, ts, nonce, body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String quote(String v) {
        var sb = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private Order call(String method, String path, String body) {
        String ts = String.valueOf(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
        var spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(baseUrl + path)
            .header("X-Pay-Timestamp", ts).header("X-Pay-Nonce", nonce)
            .header("X-Pay-Signature", sign(token, method, path, ts, nonce, body));
        if (!body.isEmpty()) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
        Map<?, ?> m = spec.retrieve().body(Map.class);
        if (m == null) throw new IllegalStateException("empty response");
        return new Order(str(m, "orderNo"), str(m, "amount"), str(m, "status"), str(m, "qrCode"), str(m, "tradeNo"));
    }
    private static String str(Map<?, ?> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }

    public Order create(String orderNo, String amount, String subject) {
        return call("POST", "/internal/payments", "{\"orderNo\":" + quote(orderNo) + ",\"amount\":" + quote(amount) + ",\"subject\":" + quote(subject) + "}");
    }
    public Order status(String orderNo) { return call("GET", "/internal/payments/" + orderNo, ""); }
}
