package cn.zzuit.campus.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import cn.zzuit.campus.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    private final IdentityService identities;
    private final LoginThrottle throttle;

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
                          HttpSessionCsrfTokenRepository csrf, IdentityService identities, LoginThrottle throttle) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.csrf = csrf;
        this.identities = identities;
        this.throttle = throttle;
    }

    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/api/auth/login")
    IdentityService.Profile login(@Valid @RequestBody LoginRequest body,
                                  HttpServletRequest request, HttpServletResponse response) {
        String username = body.username().trim();
        log.info("[AUTH | 认证] 收到用户登录请求 | 登录账号: '{}' | 来源IP: {}", username, request.getRemoteAddr());

        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            log.warn("[AUTH | 认证] 登录失败: 密码长度超过 72 字节 | 账号: '{}'", username);
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "密码不能超过 72 个 UTF-8 字节");
        }
        String throttleKey = request.getRemoteAddr() + ":" + username;
        throttle.check(throttleKey);
        try {
            var result = authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, body.password()));
            new ChangeSessionIdAuthenticationStrategy().onAuthentication(result, request, response);
            new CsrfAuthenticationStrategy(csrf).onAuthentication(result, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            throttle.succeeded(throttleKey);
            var profile = identities.profile(((CampusPrincipal) result.getPrincipal()).id());
            log.info("[AUTH | 认证] 用户登录成功 | 用户: '{}' (ID: {}, 姓名: '{}', 角色: {}) | 会话已建立",
                username, profile.id(), profile.fullName(), profile.roles());
            return profile;
        } catch (AuthenticationException ex) {
            throttle.failed(throttleKey);
            log.warn("[AUTH | 认证] 用户身份验证未通过 | 账号: '{}' | 来源IP: {} | 原因: {}",
                username, request.getRemoteAddr(), ex.getMessage());
            throw ex;
        }
    }

    @GetMapping("/api/me")
    IdentityService.Profile me(@AuthenticationPrincipal CampusPrincipal user) {
        return identities.profile(user.id());
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 128) String password) {}
}
