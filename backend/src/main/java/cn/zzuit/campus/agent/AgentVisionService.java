package cn.zzuit.campus.agent;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates and inspects fault photos for repair drafting.
 * Falls back to local metadata & filename heuristics when an external vision model is not enabled.
 */
@Service
public class AgentVisionService {
    public record PhotoAnalysis(
        String category,
        String suggestedTitle,
        String suggestedDescription,
        String resolution,
        boolean modelUsed,
        String summary
    ) {}

    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> VALID_CATEGORIES = Set.of(
        "WATER", "ELECTRICITY", "NETWORK", "FURNITURE", "DOOR_WINDOW", "EQUIPMENT", "OTHER"
    );
    private final IdentityService identities;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http;
    private final boolean visionEnabled;
    private final String visionKey;
    private final String visionModel;
    private final URI visionEndpoint;

    @Autowired
    public AgentVisionService(IdentityService identities, Environment env) {
        this.identities = identities;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        this.visionEnabled = env.getProperty("app.agent.vision.enabled", Boolean.class, false);
        this.visionKey = env.getProperty("app.agent.vision.api-key", env.getProperty("app.agent.deepseek.api-key", ""));
        this.visionModel = env.getProperty("app.agent.vision.model", "");
        String rawUri = env.getProperty("app.agent.vision.endpoint", "https://api.deepseek.com/chat/completions");
        this.visionEndpoint = URI.create(rawUri);
    }

    public PhotoAnalysis analyzeRepairPhoto(CampusPrincipal user, MultipartFile file, String note) {
        identities.requirePermission(user, "agent:query");
        identities.requirePermission(user, "repair:apply");
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "请上传有效的现场图片");
        }
        String type = Objects.toString(file.getContentType(), "").toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE", "仅支持 PNG、JPEG 或 WEBP 格式图片");
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "单张图片不能超过 5MB");
        }
        String resolution = "已验证";
        byte[] bytes;
        try {
            bytes = file.getBytes();
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                resolution = img.getWidth() + "×" + img.getHeight();
            }
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "无法读取图片内容，请检查文件是否损坏");
        }

        if (visionEnabled && !visionKey.isBlank() && !visionModel.isBlank()) {
            try {
                var parsed = callVisionModel(bytes, type, note);
                if (parsed != null) return new PhotoAnalysis(parsed.category(), parsed.suggestedTitle(), parsed.suggestedDescription(), resolution, true, parsed.summary());
            } catch (Exception ignored) {
                // Fall back to deterministic local heuristic if remote vision is unavailable.
            }
        }
        return heuristicAnalyze(file.getOriginalFilename(), note, resolution);
    }

    private PhotoAnalysis callVisionModel(byte[] bytes, String mimeType, String note) throws Exception {
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        String prompt = "你是校园报修辅助助手。仅根据图片识别故障，返回严格JSON：{\"category\":\"WATER|ELECTRICITY|NETWORK|FURNITURE|DOOR_WINDOW|EQUIPMENT|OTHER\",\"title\":\"不超过30字\",\"description\":\"不超过120字的客观现象描述\"}。禁止臆测楼栋房号与联系方式。"
            + (note == null || note.isBlank() ? "" : "补充文字：" + note.trim());
        var body = Map.of(
            "model", visionModel,
            "max_tokens", 240,
            "temperature", 0.2,
            "messages", List.of(Map.of(
                "role", "user",
                "content", List.of(
                    Map.of("type", "text", "text", prompt),
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                )
            ))
        );
        var req = HttpRequest.newBuilder(visionEndpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + visionKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        String text = json.readTree(res.body()).path("choices").path(0).path("message").path("content").asText("");
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonNode node = json.readTree(text.substring(start, end + 1));
        String cat = node.path("category").asText("OTHER");
        if (!VALID_CATEGORIES.contains(cat)) cat = "OTHER";
        String title = node.path("title").asText("").trim();
        String desc = node.path("description").asText("").trim();
        return new PhotoAnalysis(cat, title, desc, "", true, "视觉模型已完成照片故障特征识别，请核对后使用。");
    }

    private PhotoAnalysis heuristicAnalyze(String originalName, String note, String resolution) {
        String raw = (Objects.toString(originalName, "") + " " + Objects.toString(note, "")).toLowerCase(Locale.ROOT);
        record Rule(String regex, String category, String title, String desc) {}
        var rules = List.of(
            new Rule(".*(漏水|水管|水龙头|马桶|冲水|下水道|渗水|积水|水阀).*", "WATER", "给排水故障", "现场疑似存在渗漏水、阀门或排水堵塞问题，请结合实际位置补充说明。"),
            new Rule(".*(灯|插座|跳闸|开关|电路|线路|配电|照明|电源).*", "ELECTRICITY", "照明或电气故障", "现场疑似存在照明不亮、开关插座异常或供电跳闸情况，请核对是否断电。"),
            new Rule(".*(网线|wifi|宽带|路由器|网络|端口|ap).*", "NETWORK", "校园网络故障", "现场疑似存在有线端口或无线网络接入异常，请核对接口与指示灯状态。"),
            new Rule(".*(桌|椅|床|柜|木板|板凳|书架).*", "FURNITURE", "公共家具损坏", "现场疑似存在桌椅、柜体或床架松动损毁情况，请补充具体件数。"),
            new Rule(".*(门|窗|门锁|玻璃|合页|把手|插销).*", "DOOR_WINDOW", "门窗锁具故障", "现场疑似存在门锁、合页或窗户开合异常，请注意防盗与安全。"),
            new Rule(".*(空调|投影|电脑|饮水机|风扇|电梯|黑板|设备).*", "EQUIPMENT", "教学或生活设备故障", "现场疑似存在制冷、多媒体或共用设备运行异常，请核对设备状态。")
        );
        for (var r : rules) {
            if (raw.matches(r.regex())) {
                return new PhotoAnalysis(r.category(), r.title(), r.desc(), resolution, false,
                    "已完成图片格式与分辨率校验（" + resolution + "），并结合信息推荐类别「" + r.title() + "」。");
            }
        }
        return new PhotoAnalysis("", "", "", resolution, false,
            "图片核验通过（" + resolution + "），请结合现场实际填写故障类别与现象说明。");
    }
}
