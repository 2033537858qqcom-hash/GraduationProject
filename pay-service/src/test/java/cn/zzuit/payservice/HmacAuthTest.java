package cn.zzuit.payservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HmacAuthTest {
    @TestConfiguration static class Cfg { @Bean @Primary FakeGateway fake() { return new FakeGateway(); } }
    static final String TOKEN = "test-token-0123456789abcdef";
    @Autowired MockMvc mvc;

    MockHttpServletRequestBuilder signed(long ts, String nonce, String body, String token) {
        return post("/internal/payments").contentType(MediaType.APPLICATION_JSON).content(body)
            .header("X-Pay-Timestamp", String.valueOf(ts)).header("X-Pay-Nonce", nonce)
            .header("X-Pay-Signature", HmacAuthFilter.sign(token, "POST", "/internal/payments", String.valueOf(ts), nonce, body));
    }

    @Test void validSignatureThenReplayRejected() throws Exception {
        String body = "{\"orderNo\":\"HMAC00001\",\"amount\":\"1.00\",\"subject\":\"t\"}";
        long now = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        mvc.perform(signed(now, nonce, body, TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.qrCode").exists());
        mvc.perform(signed(now, nonce, body, TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test void rejectsSkewWrongKeyTamperAndMissing() throws Exception {
        String body = "{\"orderNo\":\"HMAC00002\",\"amount\":\"1.00\",\"subject\":\"t\"}";
        long now = Instant.now().getEpochSecond();
        mvc.perform(signed(now - 301, UUID.randomUUID().toString(), body, TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(signed(now, UUID.randomUUID().toString(), body, "wrong-token-0123456789")).andExpect(status().isUnauthorized());
        var tampered = signed(now, UUID.randomUUID().toString(), body, TOKEN).content(body.replace("1.00", "0.01"));
        mvc.perform(tampered).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/payments/HMAC00002")).andExpect(status().isUnauthorized());
    }

    @Test void signMatchesBackendContractVector() {
        org.junit.jupiter.api.Assertions.assertEquals("14cc1fec8567646246af275c3c4d6de24eaaa2406816e3220585a2faa5bc414e",
            HmacAuthFilter.sign("k".repeat(16), "POST", "/internal/payments", "1700000000", "nonce-123", "{}"));
    }

    @Test void notifyIsPublicButRejectsUnsigned() throws Exception {
        mvc.perform(post("/api/alipay/notify").param("out_trade_no", "x").param("sign", "bad"))
            .andExpect(status().isOk()).andExpect(content().string("failure"));
    }
}
