package cn.zzuit.campus.common.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TencentCosAvatarStorageService implements AvatarStorageService {
    public static final String LOCAL_AVATAR_URL_PREFIX = "/api/public/avatars/";
    private static final Pattern SAFE_FILENAME = Pattern.compile("^user_\\d+_[0-9a-f]{32}\\.(?:jpg|png|webp)$");
    private static final Logger log = LoggerFactory.getLogger(TencentCosAvatarStorageService.class);
    private final CosProperties properties;

    public TencentCosAvatarStorageService(CosProperties properties) {
        this.properties = properties;
    }

    private COSClient createClient() {
        var cred = new BasicCOSCredentials(properties.getSecretId(), properties.getSecretKey());
        var clientConfig = new ClientConfig(new Region(properties.getRegion()));
        return new COSClient(cred, clientConfig);
    }

    private Path localDirectory() {
        String configured = properties.getLocalAvatarDir();
        return Path.of(configured == null || configured.isBlank() ? "data/avatars" : configured).toAbsolutePath().normalize();
    }

    private static String extensionFor(String contentType) {
        if ("image/png".equalsIgnoreCase(contentType)) return ".png";
        if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
        return ".jpg";
    }

    public Path resolveLocalAvatar(String filename) {
        if (filename == null || !SAFE_FILENAME.matcher(filename).matches()) return null;
        Path root = localDirectory();
        Path resolved = root.resolve(filename).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    @Override
    public String storeAvatar(long userId, String filename, String contentType, byte[] bytes) {
        String ext = extensionFor(contentType);
        String unique = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        String safeFile = "user_" + userId + "_" + unique + ext;

        if (!properties.isEnabled() || properties.getSecretId() == null || properties.getSecretId().isBlank()
            || properties.getSecretKey() == null || properties.getSecretKey().isBlank()
            || properties.getBucket() == null || properties.getBucket().isBlank()) {
            Path root = localDirectory();
            try {
                Files.createDirectories(root);
                Path target = root.resolve(safeFile).normalize();
                if (!target.startsWith(root)) throw new IllegalStateException("非法头像路径");
                Files.write(target, bytes);
                String url = LOCAL_AVATAR_URL_PREFIX + safeFile;
                log.info("[AVATAR | 本地存储] 未配置 COS 密钥，已保存至本地头像目录 | 用户ID: {} | URL: {}", userId, url);
                return url;
            } catch (IOException e) {
                throw new RuntimeException("保存本地头像文件失败: " + e.getMessage(), e);
            }
        }

        String key = properties.getAvatarPrefix() + safeFile;
        log.info("[COS | 腾讯云] 开始直传头像文件 | 存储桶: '{}' | 地域: '{}' | 对象路径: '{}' | 大小: {} 字节",
            properties.getBucket(), properties.getRegion(), key, bytes.length);

        COSClient cosClient = createClient();
        try {
            var metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType(contentType);

            var putObjectRequest = new PutObjectRequest(properties.getBucket(), key, new ByteArrayInputStream(bytes), metadata);
            cosClient.putObject(putObjectRequest);
            String url = String.format("https://%s.cos.%s.myqcloud.com/%s", properties.getBucket(), properties.getRegion(), key);
            log.info("[COS | 腾讯云] 头像上传成功并已就绪 | 公开访问URL: {}", url);
            return url;
        } catch (Exception e) {
            log.error("[COS | 腾讯云] 上传头像至存储桶异常 | 存储桶: '{}' | 对象路径: '{}' | 异常详情: {}",
                properties.getBucket(), key, e.getMessage(), e);
            throw new RuntimeException("上传头像至腾讯云对象存储失败: " + e.getMessage(), e);
        } finally {
            cosClient.shutdown();
        }
    }

    @Override
    public void deleteAvatar(String avatarUrl) {
        if (avatarUrl == null) return;
        if (avatarUrl.startsWith(LOCAL_AVATAR_URL_PREFIX)) {
            Path path = resolveLocalAvatar(avatarUrl.substring(LOCAL_AVATAR_URL_PREFIX.length()));
            if (path != null) {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { log.warn("[AVATAR | 本地存储] 清理旧头像失败: '{}' | 原因: {}", path, e.getMessage()); }
            }
            return;
        }
        if (!avatarUrl.startsWith("https://") || !properties.isEnabled()) return;
        if (properties.getBucket() == null || properties.getRegion() == null) return;

        String domain = String.format("%s.cos.%s.myqcloud.com/", properties.getBucket(), properties.getRegion());
        if (!avatarUrl.contains(domain)) return;

        String key = avatarUrl.substring(avatarUrl.indexOf(domain) + domain.length());
        log.info("[COS | 腾讯云] 准备清理历史头像对象 | 存储桶: '{}' | 对象路径: '{}'", properties.getBucket(), key);
        COSClient cosClient = createClient();
        try {
            cosClient.deleteObject(properties.getBucket(), key);
            log.info("[COS | 腾讯云] 历史头像对象清理完成: '{}'", key);
        } catch (Exception e) {
            log.warn("[COS | 腾讯云] 清理历史头像对象失败: '{}' | 原因: {}", key, e.getMessage());
        } finally {
            cosClient.shutdown();
        }
    }
}
