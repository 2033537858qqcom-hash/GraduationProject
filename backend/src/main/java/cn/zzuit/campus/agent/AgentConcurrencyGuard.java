package cn.zzuit.campus.agent;

import cn.zzuit.campus.common.ApiException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Per-user in-flight lock and minute-window rate limiter for assistant queries and draft preparations. */
@Component
public class AgentConcurrencyGuard {
    private record Bucket(long minute, int count) {}

    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private final Map<Long, Bucket> rates = new ConcurrentHashMap<>();

    public void acquireTurn(long userId, String runningMessage) {
        if (!busy.add(userId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUERY_RUNNING", runningMessage);
        }
    }

    public void releaseTurn(long userId) {
        busy.remove(userId);
    }

    public void checkRate(long userId) {
        long minute = Instant.now().getEpochSecond() / 60;
        rates.entrySet().removeIf(e -> e.getValue().minute() < minute - 2);
        var bucket = rates.compute(
            userId,
            (k, b) -> b == null || b.minute() != minute ? new Bucket(minute, 1) : new Bucket(minute, b.count() + 1));
        if (bucket.count() > 30) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUERY_RATE_LIMIT", "查询较频繁，请稍后重试");
        }
    }
}
