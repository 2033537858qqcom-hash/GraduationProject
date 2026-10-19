package cn.zzuit.campus;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:avatar-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true",
    "app.cos.enabled=false" // tests local fallback gracefully
})
class UserAvatarIntegrationTest {
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void avatarRequiresLogin() throws Exception {
        var browser = new Browser();
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    void uploadAndRemoveAvatarWorkflow() throws Exception {
        var student = login("20260001", "Demo@2026");

        // 1. Initial profile has null avatar
        var meRes = student.get("/api/me");
        assertThat(meRes.statusCode()).isEqualTo(200);
        var meNode = json.readTree(meRes.body());
        assertThat(meNode.path("avatarUrl").isNull()).isTrue();

        // 2. Upload avatar with multipart form data
        byte[] imageBytes = new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }; // PNG header
        var uploadRes = student.uploadFile("/api/me/avatar", "avatar.png", "image/png", imageBytes);
        assertThat(uploadRes.statusCode()).as(uploadRes.body()).isEqualTo(200);
        var uploadNode = json.readTree(uploadRes.body());
        String avatarUrl = uploadNode.path("avatarUrl").asText();
        assertThat(avatarUrl).isNotBlank();

        // 3. Verify /api/me reflects updated avatarUrl
        var updatedMe = student.get("/api/me");
        assertThat(updatedMe.statusCode()).isEqualTo(200);
        assertThat(json.readTree(updatedMe.body()).path("avatarUrl").asText()).isEqualTo(avatarUrl);

        // 4. Delete avatar
        var deleteRes = student.send("DELETE", "/api/me/avatar", null, null);
        assertThat(deleteRes.statusCode()).isEqualTo(200);
        var deletedNode = json.readTree(deleteRes.body());
        assertThat(deletedNode.path("avatarUrl").isNull()).isTrue();

        // 5. Verify /api/me reflects null after removal
        var clearedMe = student.get("/api/me");
        assertThat(json.readTree(clearedMe.body()).path("avatarUrl").isNull()).isTrue();
    }

    @Test
    void tencentCosDirectUploadAndCleanup() {
        String secretId = System.getenv("COS_SECRET_ID");
        String secretKey = System.getenv("COS_SECRET_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(secretId != null && !secretId.isBlank() && secretKey != null && !secretKey.isBlank(), "COS credentials not configured in env");
        var properties = new cn.zzuit.campus.common.storage.CosProperties();
        properties.setEnabled(true);
        properties.setSecretId(secretId);
        properties.setSecretKey(secretKey);
        properties.setRegion(System.getenv().getOrDefault("COS_REGION", "ap-guangzhou"));
        properties.setBucket(System.getenv().getOrDefault("COS_BUCKET", "shiyan-bbs-01-1429420924"));
        properties.setAvatarPrefix("avatars/");

        var cosService = new cn.zzuit.campus.common.storage.TencentCosAvatarStorageService(properties);
        byte[] testBytes = new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        String url = cosService.storeAvatar(999999L, "test.png", "image/png", testBytes);
        assertThat(url).contains(".cos.ap-guangzhou.myqcloud.com/avatars/user_999999_");

        // Clean up
        cosService.deleteAvatar(url);
    }

    Browser login(String username, String password) throws Exception {
        var browser = new Browser();
        browser.csrf();
        var payload = json.writeValueAsBytes(Map.of("username", username, "password", password));
        var res = browser.send("POST", "/api/auth/login", payload, "application/json");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        browser.csrf();
        return browser;
    }

    class Browser {
        final HttpClient client;
        String csrfHeader;
        String csrfToken;

        Browser() {
            var manager = new CookieManager();
            manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            this.client = HttpClient.newBuilder().cookieHandler(manager).build();
        }

        void csrf() throws Exception {
            var data = json.readTree(get("/api/auth/csrf").body());
            csrfHeader = data.path("headerName").asText();
            csrfToken = data.path("token").asText();
        }

        HttpResponse<String> get(String path) throws Exception {
            var req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).GET().build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, byte[] body, String contentType) throws Exception {
            var builder = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path));
            if (csrfHeader != null && csrfToken != null) {
                builder.header(csrfHeader, csrfToken);
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
            builder.method(method, publisher);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> uploadFile(String path, String filename, String contentType, byte[] bytes) throws Exception {
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: " + contentType + "\r\n\r\n";
            String footer = "\r\n--" + boundary + "--\r\n";

            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
            byte[] fullPayload = new byte[headerBytes.length + bytes.length + footerBytes.length];

            System.arraycopy(headerBytes, 0, fullPayload, 0, headerBytes.length);
            System.arraycopy(bytes, 0, fullPayload, headerBytes.length, bytes.length);
            System.arraycopy(footerBytes, 0, fullPayload, headerBytes.length + bytes.length, footerBytes.length);

            return send("POST", path, fullPayload, "multipart/form-data; boundary=" + boundary);
        }
    }
}
