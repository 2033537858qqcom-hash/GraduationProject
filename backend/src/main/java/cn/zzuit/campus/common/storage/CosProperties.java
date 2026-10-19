package cn.zzuit.campus.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.cos")
public class CosProperties {
    private boolean enabled = true;
    private String secretId;
    private String secretKey;
    private String region = "ap-guangzhou";
    private String bucket;
    private String appid;
    private String avatarPrefix = "avatars/";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSecretId() { return secretId; }
    public void setSecretId(String secretId) { this.secretId = secretId; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getAppid() { return appid; }
    public void setAppid(String appid) { this.appid = appid; }

    public String getAvatarPrefix() { return avatarPrefix; }
    public void setAvatarPrefix(String avatarPrefix) { this.avatarPrefix = avatarPrefix; }
}
