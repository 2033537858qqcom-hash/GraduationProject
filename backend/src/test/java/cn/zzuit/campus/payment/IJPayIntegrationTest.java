package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.DispatcherServlet;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:ijpay-integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=false", "app.cos.enabled=false", "app.agent.deepseek.enabled=false",
    "app.repair.reminders.enabled=false"
})
class IJPayIntegrationTest {
    @LocalServerPort int port;

    @Test void campusHealthEndpointStartsWithIJPayOnTheRealApplicationClasspath() throws Exception {
        var response = get("/api/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test void addingCompatibilityTypesPreservesExistingAuthentication() throws Exception {
        assertThat(get("/api/me").statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/csrf").statusCode()).isEqualTo(200);
    }

    @Test void springDispatcherStillUsesJakartaServletTypes() throws Exception {
        assertThat(jakarta.servlet.http.HttpServlet.class.isAssignableFrom(DispatcherServlet.class)).isTrue();
        assertThat(Class.forName("javax.servlet.http.HttpServlet").isAssignableFrom(DispatcherServlet.class)).isFalse();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
