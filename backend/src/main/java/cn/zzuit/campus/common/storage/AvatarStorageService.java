package cn.zzuit.campus.common.storage;

public interface AvatarStorageService {
    String storeAvatar(long userId, String filename, String contentType, byte[] bytes);
    void deleteAvatar(String avatarUrl);
}
