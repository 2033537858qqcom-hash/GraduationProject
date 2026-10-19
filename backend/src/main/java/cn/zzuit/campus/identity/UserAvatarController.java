package cn.zzuit.campus.identity;

import java.io.IOException;
import java.util.Set;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.common.storage.AvatarStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UserAvatarController {
    private static final Logger log = LoggerFactory.getLogger(UserAvatarController.class);

    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024; // 2MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/jpg"
    );

    private final IdentityService identities;
    private final AvatarStorageService storageService;

    public UserAvatarController(IdentityService identities, AvatarStorageService storageService) {
        this.identities = identities;
        this.storageService = storageService;
    }

    @PostMapping("/api/me/avatar")
    public IdentityService.Profile uploadAvatar(@AuthenticationPrincipal CampusPrincipal user,
                                                @RequestParam("file") MultipartFile file) {
        log.info("[AVATAR | 头像] 收到头像上传请求 | 用户: {} (ID: {}) | 原文件名: '{}' | 文件大小: {} 字节 ({} KB) | 类型: '{}'",
            user.username(), user.id(),
            file != null ? file.getOriginalFilename() : "null",
            file != null ? file.getSize() : 0,
            file != null ? (file.getSize() / 1024) : 0,
            file != null ? file.getContentType() : "null");

        if (file == null || file.isEmpty()) {
            log.warn("[AVATAR | 头像] 头像上传失败: 未选择文件或文件内容为空 | 用户ID: {}", user.id());
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请选择需要上传的头像图片");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            log.warn("[AVATAR | 头像] 头像上传失败: 文件大小超限 ({} 字节 > 2MB) | 用户ID: {}", file.getSize(), user.id());
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "头像图片不能超过 2 MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            log.warn("[AVATAR | 头像] 头像上传失败: 不支持的图片格式 '{}' | 用户ID: {}", contentType, user.id());
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "头像仅支持 JPG、PNG 或 WebP 格式图片");
        }

        try {
            var current = identities.profile(user.id());
            String newUrl = storageService.storeAvatar(user.id(), file.getOriginalFilename(), contentType, file.getBytes());
            if (current.avatarUrl() != null && !current.avatarUrl().equals(newUrl)) {
                storageService.deleteAvatar(current.avatarUrl());
            }
            var updated = identities.updateAvatar(user.id(), newUrl);
            log.info("[AVATAR | 头像] 头像上传并更新成功 | 用户: {} (ID: {}) | 头像地址: {}", user.username(), user.id(), newUrl);
            return updated;
        } catch (IOException e) {
            log.error("[AVATAR | 头像] 读取上传头像数据发生IO异常 | 用户ID: {}", user.id(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR", "读取上传的头像文件失败，请重试");
        }
    }

    @DeleteMapping("/api/me/avatar")
    public IdentityService.Profile removeAvatar(@AuthenticationPrincipal CampusPrincipal user) {
        log.info("[AVATAR | 头像] 收到恢复默认头像请求 | 用户: {} (ID: {})", user.username(), user.id());
        var current = identities.profile(user.id());
        if (current.avatarUrl() != null) {
            storageService.deleteAvatar(current.avatarUrl());
            log.info("[AVATAR | 头像] 已清理旧头像存储资源 | 用户ID: {} | 旧地址: {}", user.id(), current.avatarUrl());
        }
        var updated = identities.removeAvatar(user.id());
        log.info("[AVATAR | 头像] 已恢复系统默认头像 | 用户: {} (ID: {})", user.username(), user.id());
        return updated;
    }
}
