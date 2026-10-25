package cn.zzuit.campus.common;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException ex, HttpServletRequest request) {
        LOG.warn("[API-WARN | 业务警告] {} {} -> 错误码: {}, 原因: {} (HTTP {})",
            request.getMethod(), request.getRequestURI(), ex.code(), ex.getMessage(), ex.status().value());
        return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, String>> authentication(AuthenticationException ex, HttpServletRequest request) {
        LOG.warn("[AUTH-FAIL | 认证失败] {} {} -> 凭证错误或未授权: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("code", "LOGIN_FAILED", "message", "账号或密码不正确"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden(AccessDeniedException ex, HttpServletRequest request) {
        LOG.warn("[FORBIDDEN | 权限拒绝] {} {} -> 当前用户权限不足以执行此操作", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("code", "FORBIDDEN", "message", "当前身份没有此操作权限"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        LOG.warn("[VALID-FAIL | 校验失败] {} {} -> 字段错误: {}", request.getMethod(), request.getRequestURI(), fields);
        return ResponseEntity.badRequest().body(Map.of(
            "code", "VALIDATION_FAILED", "message", "请检查填写内容", "fields", fields));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        LOG.warn("[CONFLICT | 数据冲突] {} {} -> 数据完整性约束冲突: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", "DATA_CONFLICT", "message", "内容标识已存在或关联数据发生变化"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, String>> malformedRequest(Exception ex, HttpServletRequest request) {
        LOG.warn("[BAD-REQ | 请求畸变] {} {} -> 参数类型或消息体格式不正确: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
            .body(Map.of("code", "INVALID_REQUEST", "message", "请求格式或参数不正确"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> missingResource(NoResourceFoundException ex, HttpServletRequest request) {
        LOG.warn("[NOT-FOUND | 资源未找到] {} {} -> 找不到目标资源", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("code", "NOT_FOUND", "message", "请求的资源不存在"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> methodNotAllowed(HttpServletRequest request) {
        LOG.warn("[NOT-ALLOWED | 方法不支持] {} {} -> 此端点不支持该 HTTP 方法", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(Map.of("code", "METHOD_NOT_ALLOWED", "message", "不支持此操作"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> oversizedFile(HttpServletRequest request) {
        LOG.warn("[PAYLOAD-LARGE | 文件超限] {} {} -> 上传文件超出最大限制 (2MB)", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(Map.of("code", "FILE_TOO_LARGE", "message", "附件最大为 2 MB"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception ex, HttpServletRequest request) {
        LOG.error("[API-ERR | 系统异常] {} {} -> 服务处理未捕获异常: {}", request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
            .body(Map.of("code", "INTERNAL_ERROR", "message", "服务暂时不可用，请稍后重试"));
    }
}
