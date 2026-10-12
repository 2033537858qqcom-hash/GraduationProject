package cn.zzuit.campus.identity;

import cn.zzuit.campus.common.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LoginThrottle {
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();

    public synchronized void check(String key) {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        Attempt attempt = attempts.get(key);
        if (attempt != null && attempt.failures() >= 8) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "尝试次数较多，请五分钟后再试");
        }
    }

    public synchronized void failed(String key) {
        if (attempts.size() >= 2048 && !attempts.containsKey(key)) {
            attempts.remove(attempts.keySet().iterator().next());
        }
        Attempt previous = attempts.get(key);
        attempts.put(key, previous == null
            ? new Attempt(1, Instant.now().plusSeconds(300))
            : new Attempt(previous.failures() + 1, previous.expiresAt()));
    }

    public synchronized void succeeded(String key) { attempts.remove(key); }
    private record Attempt(int failures, Instant expiresAt) {}
}
