package cn.zzuit.payservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /internal/** 鉴权：X-Pay-Timestamp(秒)、X-Pay-Nonce、X-Pay-Signature = hex(HMAC-SHA256(token,
 * METHOD\nPATH\nTIMESTAMP\nNONCE\nBODY))。时间偏差超过 5 分钟或 nonce 重放一律拒绝。
 */
@Component
public class HmacAuthFilter extends OncePerRequestFilter {
    private final String token;
    private final JdbcClient jdbc;
    Clock clock = Clock.systemUTC();

    public HmacAuthFilter(@Value("${pay.internal-token}") String token, JdbcClient jdbc) { this.token = token; this.jdbc = jdbc; }

    public static String sign(String token, String method, String path, String ts, String nonce, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.join("\n", method, path, ts, nonce, body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest req) { return !req.getRequestURI().startsWith("/internal/"); }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (token == null || token.length() < 16) { res.sendError(503, "internal token not configured"); return; }
        byte[] body = req.getInputStream().readAllBytes();
        String ts = req.getHeader("X-Pay-Timestamp"), nonce = req.getHeader("X-Pay-Nonce"), sig = req.getHeader("X-Pay-Signature");
        if (ts == null || nonce == null || sig == null || !nonce.matches("[A-Za-z0-9-]{8,64}")) { res.sendError(401); return; }
        long t;
        try { t = Long.parseLong(ts); } catch (NumberFormatException e) { res.sendError(401); return; }
        if (Math.abs(clock.instant().getEpochSecond() - t) > 300) { res.sendError(401); return; }
        String expected = sign(token, req.getMethod(), req.getRequestURI(), ts, nonce, new String(body, StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.toLowerCase().getBytes(StandardCharsets.UTF_8))) { res.sendError(401); return; }
        try {
            jdbc.sql("INSERT INTO used_nonce(nonce,seen_at) VALUES(:n,:t)").param("n", nonce).param("t", Timestamp.from(Instant.now())).update();
        } catch (DuplicateKeyException e) { res.sendError(401, "replayed"); return; }
        chain.doFilter(new CachedBodyRequest(req, body), res);
    }

    static class CachedBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] body;
        CachedBodyRequest(HttpServletRequest r, byte[] body) { super(r); this.body = body; }
        @Override public jakarta.servlet.ServletInputStream getInputStream() {
            var in = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                public boolean isFinished() { return in.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(jakarta.servlet.ReadListener l) {}
                public int read() { return in.read(); }
            };
        }
        @Override public java.io.BufferedReader getReader() { return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
