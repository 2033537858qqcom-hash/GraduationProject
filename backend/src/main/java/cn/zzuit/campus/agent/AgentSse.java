package cn.zzuit.campus.agent;

import cn.zzuit.campus.common.ApiException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Shared SSE runner for long-lived AI query, mission, and goal executions. */
final class AgentSse {
    private AgentSse() {}

    static <T> SseEmitter stream(String threadName, long timeoutMs, String initialStep, Function<Consumer<String>, T> task) {
        var emitter = new SseEmitter(timeoutMs);
        var closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(ex -> closed.set(true));
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                emit(emitter, closed, "step", Map.of("message", initialStep));
                T result = task.apply(step -> emit(emitter, closed, "step", Map.of("message", step)));
                emit(emitter, closed, "result", result);
                if (!closed.get()) emitter.complete();
            } catch (ApiException ex) {
                emit(emitter, closed, "error", Map.of("code", ex.code(), "message", ex.getMessage(), "status", ex.status().value()));
                if (!closed.get()) emitter.complete();
            } catch (RuntimeException ex) {
                emit(emitter, closed, "error", Map.of("code", "QUERY_FAILED", "message", "处理失败，请稍后重试", "status", 500));
                if (!closed.get()) emitter.complete();
            }
        });
        return emitter;
    }

    static void emit(SseEmitter emitter, AtomicBoolean closed, String event, Object payload) {
        if (closed.get()) return;
        synchronized (emitter) {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                closed.set(true);
            }
        }
    }
}
