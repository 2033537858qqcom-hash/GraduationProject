package cn.zzuit.campus.common;

import java.io.IOException;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 后端统一 API 操作审计与请求日志过滤器。
 * 记录客户端 IP、请求方法、URI、执行耗时、HTTP 状态码及操作人信息。
 */
public class OperationLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger("cn.zzuit.campus.audit.OperationAudit");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();

        // 仅拦截审计 API 接口及错误处理端点
        if (!uri.startsWith("/api") && !uri.equals("/error")) {
            filterChain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            int status = response.getStatus();
            String method = request.getMethod();
            String queryString = request.getQueryString();
            String fullPath = (queryString != null && !queryString.isBlank()) ? uri + "?" + queryString : uri;
            String clientIp = resolveClientIp(request);
            String userDesc = resolveUserDesc(request);

            String logMsg = String.format("[OP-AUDIT | 操作审计] %-6s %-32s -> Status: %-3d | Latency: %-4dms | User: %-28s | IP: %s",
                method, fullPath, status, duration, userDesc, clientIp);

            if (status >= 500) {
                LOG.error(logMsg);
            } else if (status >= 400) {
                LOG.warn(logMsg);
            } else {
                LOG.info(logMsg);
            }
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveUserDesc(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if ((auth == null || !auth.isAuthenticated()) && request.getSession(false) != null) {
            Object secCtx = request.getSession(false).getAttribute("SPRING_SECURITY_CONTEXT");
            if (secCtx instanceof SecurityContext sc) {
                auth = sc.getAuthentication();
            }
        }

        if (auth != null && auth.isAuthenticated()) {
            Object principal = auth.getPrincipal();
            if (principal instanceof CampusPrincipal cp) {
                return String.format("%s (id:%d, roles:%s)", cp.username(), cp.id(), cp.roles());
            } else if (!"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        }
        return "anonymous";
    }
}
