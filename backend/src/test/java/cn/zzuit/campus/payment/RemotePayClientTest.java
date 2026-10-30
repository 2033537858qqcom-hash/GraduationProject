package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RemotePayClientTest {
    @Test void signatureMatchesPayServiceContract() {
        assertThat(RemotePayClient.sign("k".repeat(16),"POST","/internal/payments","1700000000","nonce-123","{}"))
            .isEqualTo("14cc1fec8567646246af275c3c4d6de24eaaa2406816e3220585a2faa5bc414e");
    }
    @Test void jsonQuotingEscapes() {
        assertThat(RemotePayClient.quote("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\u000a\"");
    }
}
