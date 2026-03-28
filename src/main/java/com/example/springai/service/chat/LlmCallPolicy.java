package com.example.springai.service.chat;

import com.example.springai.service.exception.ChatProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * LLM 호출 공통 정책(최소 간격, 재시도, 백오프)을 한 곳에서 관리한다.
 */
@Component
public class LlmCallPolicy {

    private static final Logger logger = LoggerFactory.getLogger(LlmCallPolicy.class);

    private final LlmRequestRateLimiter rateLimiter;

    public LlmCallPolicy(LlmRequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public <T> T executeSync(String modelName, Supplier<T> call) {
        int maxRetries = rateLimiter.maxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                rateLimiter.acquire();
                return call.get();
            } catch (WebClientResponseException e) {
                boolean retriable = isRetriableError(e);
                boolean hasNextAttempt = attempt < maxRetries;

                if (retriable && hasNextAttempt) {
                    Duration retryDelay = resolveRetryDelay(e, attempt);
                    rateLimiter.applyBackoff(retryDelay);
                    logger.warn("{} API throttled/unavailable (status={}): retry {}/{} in {} ms",
                            modelName, e.getStatusCode(), attempt + 1, maxRetries, retryDelay.toMillis());
                    continue;
                }
                throw toChatProcessingException(modelName, e);
            } catch (Exception e) {
                throw new ChatProcessingException("API call failed: " + e.getMessage(), e);
            }
        }
        throw new ChatProcessingException("API call failed after retry exhaustion", null);
    }

    public Retry streamRetrySpec(String modelName) {
        long baseRetryDelayMs = Math.max(rateLimiter.initialBackoffMs(), rateLimiter.minIntervalMs());
        return Retry.backoff(rateLimiter.maxRetries(), Duration.ofMillis(baseRetryDelayMs))
                .maxBackoff(Duration.ofMillis(rateLimiter.maxBackoffMs()))
                .filter(this::isRetriableError)
                .doBeforeRetry(signal -> {
                    Duration delay = resolveRetryDelay(signal.failure(), signal.totalRetriesInARow());
                    rateLimiter.applyBackoff(delay);
                    rateLimiter.acquire();
                    logger.warn("{} stream throttled/unavailable: retry {}/{} in {} ms",
                            modelName,
                            signal.totalRetriesInARow() + 1,
                            rateLimiter.maxRetries(),
                            delay.toMillis());
                });
    }

    public void acquireBeforeStream() {
        rateLimiter.acquire();
    }

    public ChatProcessingException toChatProcessingException(WebClientResponseException exception) {
        return toChatProcessingException("llm", exception);
    }

    public ChatProcessingException toChatProcessingException(String modelName, WebClientResponseException exception) {
        String normalizedModelName = (modelName == null || modelName.isBlank()) ? "llm" : modelName;
        if (exception.getStatusCode().value() == 401) {
            String guidance = switch (normalizedModelName.toLowerCase()) {
                case "openai" -> "Verify OPENAI_API_KEY (or HTTP_OPENAI_API_KEY / OPEN_API_KEY) and model access.";
                case "gemini" -> "Verify HTTP_GEMINI_API_KEY and model access.";
                case "mistral" -> "Verify MISTRAL_API_KEY and model access.";
                case "gemma" -> "Verify GEMMA_API_KEY (or HTTP_GEMINI_API_KEY) and model access.";
                default -> "Verify API key and model access permissions.";
            };
            return new ChatProcessingException(
                    String.format("%s API authentication failed: 401 Unauthorized. %s", normalizedModelName, guidance),
                    exception
            );
        }
        return new ChatProcessingException(
                "API call failed: " + exception.getStatusCode() + " " + exception.getStatusText(),
                exception
        );
    }

    private boolean isRetriableError(Throwable throwable) {
        if (throwable instanceof WebClientResponseException webClientException) {
            HttpStatusCode statusCode = webClientException.getStatusCode();
            return statusCode.value() == 429 || statusCode.is5xxServerError();
        }
        return false;
    }

    private Duration resolveRetryDelay(Throwable throwable, long retryCount) {
        if (throwable instanceof WebClientResponseException webClientException) {
            Duration headerDelay = parseRetryAfter(webClientException);
            if (headerDelay != null && !headerDelay.isNegative() && !headerDelay.isZero()) {
                return clampDelay(headerDelay);
            }
        }

        long multiplier = 1L << Math.min(retryCount, 20L);
        long delayMs = rateLimiter.initialBackoffMs() * multiplier;
        long jitterMs = ThreadLocalRandom.current().nextLong(100L, 401L);
        return clampDelay(Duration.ofMillis(delayMs + jitterMs));
    }

    private Duration clampDelay(Duration delay) {
        long clampedMs = Math.min(Math.max(1L, delay.toMillis()), rateLimiter.maxBackoffMs());
        return Duration.ofMillis(clampedMs);
    }

    private Duration parseRetryAfter(WebClientResponseException exception) {
        String retryAfter = exception.getHeaders().getFirst("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }

        String raw = retryAfter.trim();
        try {
            long seconds = Long.parseLong(raw);
            return Duration.ofSeconds(Math.max(0L, seconds));
        } catch (NumberFormatException ignored) {
            // Retry-After can also be an HTTP date.
        }

        try {
            Instant retryInstant = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long millis = Duration.between(Instant.now(), retryInstant).toMillis();
            return Duration.ofMillis(Math.max(0L, millis));
        } catch (DateTimeParseException ignored) {
            logger.debug("Unable to parse Retry-After header: {}", raw);
            return null;
        }
    }
}
