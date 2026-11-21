package com.jianli.ai;

import com.jianli.config.AiProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AI 调用执行器，统一处理开关、线程隔离、超时、校验和降级。
 *
 * 当前项目没有接入外部模型；这个执行器只保留可复用的调用边界。
 * 业务代码不能绕过 AiFeatureGate 直接调用模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallExecutor {

    private final ExecutorService aiExecutor = new ThreadPoolExecutor(
            8,
            8,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-call");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final AiFeatureGate gate;
    private final AiProperties props;

    public <T> AiResult<T> execute(AiFeature feature,
                                   Supplier<T> aiCall,
                                   Consumer<T> validator,
                                   Supplier<T> fallback) {
        if (!gate.isAvailable(feature)) {
            return AiResult.fallback(fallback.get(), "AI_DISABLED");
        }

        long start = System.currentTimeMillis();
        Future<T> future = null;

        try {
            future = ((ThreadPoolExecutor) aiExecutor).submit(aiCall::get);
            T result = future.get(timeoutOf(feature).toMillis(), TimeUnit.MILLISECONDS);

            if (validator != null) {
                validator.accept(result);
            }

            log.info("AI [{}] 调用成功，耗时 {}ms", feature, System.currentTimeMillis() - start);
            return AiResult.ai(result);
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }

            String reason = mapReason(e);
            log.warn("AI [{}] 调用失败，耗时 {}ms，原因 {}，已降级",
                    feature, System.currentTimeMillis() - start, reason, e);

            try {
                return AiResult.fallback(fallback.get(), reason);
            } catch (Exception fallbackError) {
                log.error("AI [{}] 降级实现也失败了", feature, fallbackError);
                throw new IllegalStateException("AI 与降级实现均失败", fallbackError);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        aiExecutor.shutdownNow();
    }

    private String mapReason(Throwable exception) {
        Throwable target = exception;
        while ((target instanceof ExecutionException || target instanceof java.util.concurrent.CompletionException)
                && target.getCause() != null) {
            target = target.getCause();
        }

        if (target instanceof TimeoutException) {
            return "AI_TIMEOUT";
        }
        if (target instanceof AiOutputInvalidException) {
            return "AI_OUTPUT_INVALID";
        }

        String message = target.getMessage() == null
                ? ""
                : target.getMessage().toLowerCase(java.util.Locale.ROOT);

        if (message.contains("timeout") || message.contains("timed out")) {
            return "AI_TIMEOUT";
        }
        if (message.contains("429") || message.contains("quota") || message.contains("rate limit")) {
            return "AI_QUOTA_EXCEEDED";
        }
        if (message.contains("401") || message.contains("403")
                || message.contains("unauthorized") || message.contains("forbidden")) {
            return "AI_AUTH_FAILED";
        }
        if (message.contains("402") || message.contains("balance") || message.contains("payment")) {
            return "AI_QUOTA_EXCEEDED";
        }
        return "AI_CALL_FAILED";
    }

    private Duration timeoutOf(AiFeature feature) {
        String key = switch (feature) {
            case JD_PARSE -> "jd-parse";
            case RESUME_PARSE -> "resume-parse";
            case SUGGESTIONS -> "suggestions";
            case REWRITE -> "rewrite";
            case INTERVIEW -> "interview";
            case PRIVACY_ASSIST -> "privacy-assist";
        };

        Duration configured = props.timeout() == null ? null : props.timeout().get(key);
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return Duration.ofSeconds(25);
        }
        return configured;
    }
}