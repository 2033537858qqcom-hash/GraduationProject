package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:payment-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true", "app.cos.enabled=false", "app.repair.reminders.enabled=false"})
class PaymentHttpTest {
    @LocalServerPort int port;
    @Test void callbackIsPublicButUnverifiedCallsCannotSucceed() throws Exception {
        var request=HttpRequest.newBuilder(uri("/api/payments/alipay/notify"))
            .header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("trade_status=TRADE_SUCCESS")).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("failure");
    }
    @Test void billsRequireLoginAndCallbackExemptionDoesNotCoverOtherMethods() throws Exception {
        var client=HttpClient.newHttpClient();
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/payments/mine")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/payments/alipay/notify")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:"+port+path); }
}
