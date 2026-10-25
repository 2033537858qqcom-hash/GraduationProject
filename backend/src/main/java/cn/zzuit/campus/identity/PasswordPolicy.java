package cn.zzuit.campus.identity;

import cn.zzuit.campus.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String value) {
        if (value == null || value.length() < 10 || value.getBytes(StandardCharsets.UTF_8).length > 72
            || !value.matches("(?s).*[A-Za-z].*") || !value.matches("(?s).*[0-9].*"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NEW_PASSWORD","新密码至少十位，包含字母和数字，且不超过 72 个 UTF-8 字节");
    }
}
