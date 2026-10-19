package cn.zzuit.campus.common.storage;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.UUID;
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

    @Override
    public String storeAvatar(long userId, String filename, String contentType, byte[] bytes) {
        if (!properties.isEnabled() || properties.getSecretId() == null || properties.getSecretId().isBlank()
            || properties.getSecretKey() == null || properties.getSecretKey().isBlank()
            || properties.getBucket() == null || properties.getBucket().isBlank()) {
            log.info("[COS | 腾讯云] 未启用或缺少存储桶配置，降级使用 Data URI 存储 (用户ID: {})", userId);
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }

        String ext = ".jpg";
        if (filename != null && filename.contains(".")) {
            ext = filename.substring(filename.lastIndexOf(".")).toLowerCase();
        } else if ("image/png".equalsIgnoreCase(contentType)) {
            ext = ".png";
        } else if ("image/webp".equalsIgnoreCase(contentType)) {
            ext = ".webp";
        }

        String key = properties.getAvatarPrefix() + "user_" + userId + "_" + UUID.randomUUID().toString().replace("-", "") + ext;
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
        if (avatarUrl == null || !avatarUrl.startsWith("https://") || !properties.isEnabled()) {
            return;
        }
        if (properties.getBucket() == null || properties.getRegion() == null) {
            return;
        }

        String domain = String.format("%s.cos.%s.myqcloud.com/", properties.getBucket(), properties.getRegion());
        if (!avatarUrl.contains(domain)) {
            return;
        }

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
