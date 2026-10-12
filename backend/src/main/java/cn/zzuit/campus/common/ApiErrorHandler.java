package cn.zzuit.campus.common;

import java.util.LinkedHashMap;
import java.util.Map;
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
    ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, String>> authentication() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("code", "LOGIN_FAILED", "message", "账号或密码不正确"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("code", "FORBIDDEN", "message", "当前身份没有此操作权限"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
            "code", "VALIDATION_FAILED", "message", "请检查填写内容", "fields", fields));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", "DATA_CONFLICT", "message", "内容标识已存在或关联数据发生变化"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> malformedRequest() {
        return ResponseEntity.badRequest()
            .body(Map.of("code", "INVALID_REQUEST", "message", "请求格式或参数不正确"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> missingResource() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("code", "NOT_FOUND", "message", "请求的资源不存在"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> oversizedFile() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(Map.of("code", "FILE_TOO_LARGE", "message", "附件最大为 2 MB"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception ex) {
        LOG.error("Unhandled server error", ex);
        return ResponseEntity.internalServerError()
            .body(Map.of("code", "INTERNAL_ERROR", "message", "服务暂时不可用，请稍后重试"));
    }
}
