package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.exception.DownstreamA2AException;
import com.example.springsupervisorai.service.agent.invoke.A2AClientRegistry;
import com.example.springsupervisorai.service.resilience.CircuitBreakerUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class A2AJsonRpcClient {

    private static final Logger logger = LoggerFactory.getLogger(A2AJsonRpcClient.class);
    private static final String A2A_SESSION_HEADER = "X-A2A-Session-Id";
    private static final String DONE_TOKEN = "[DONE]";
    private final WebClient.Builder webClientBuilder;

    public A2AJsonRpcClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public JsonNode call(A2AClientRegistry.A2ARouteTarget target, JsonRpcRequest request, String sessionId) {
        try {
            return CircuitBreakerUtils.executeA2A((Supplier<JsonNode>) () -> {
                return webClientBuilder.build()
                        .post()
                        .uri(target.endpoint())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(A2A_SESSION_HEADER, safeSessionId(sessionId))
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(com.fasterxml.jackson.databind.JsonNode.class)
                        .timeout(target.timeout())
                        .onErrorMap(ex -> new DownstreamA2AException("Downstream A2A call failed: " + target.agentKey(), ex))
                        .blockOptional()
                        .orElseThrow(() -> new DownstreamA2AException("Empty downstream response: " + target.agentKey()));
            });
        } catch (CircuitBreakerUtils.CircuitBreakerOpenException ex) {
            throw new DownstreamA2AException("Downstream A2A circuit breaker is open for: " + target.agentKey(), ex);
        } catch (DownstreamA2AException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DownstreamA2AException("Downstream A2A call failed: " + target.agentKey(), ex);
        }
    }

    public String callStream(A2AClientRegistry.A2ARouteTarget target, JsonRpcRequest request, String sessionId) {
        try {
            List<String> chunks = webClientBuilder.build()
                    .post()
                    .uri(target.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(A2A_SESSION_HEADER, safeSessionId(sessionId))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .onErrorMap(ex -> new DownstreamA2AException("Downstream A2A stream failed: " + target.agentKey(), ex))
                    /*
                     * 하위 에이전트가 done 신호를 보낸 경우 연결 close를 기다리지 않고 즉시 수집을 종료한다.
                     * - 누적 호출 중 SSE 세션이 keep-alive 상태로 남아 supervisor 120초 타임아웃이 나는 문제를 줄인다.
                     */
                    .takeUntil(this::containsTerminalSignal)
                    .collectList()
                    .block(target.timeout());
            return mergeSseChunks(chunks);
        } catch (DownstreamA2AException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DownstreamA2AException("Downstream A2A stream failed: " + target.agentKey(), ex);
        }
    }

    /**
     * timeout/연결 오류 발생 시 downstream 세션 정리를 시도한다.
     * <p>
     * 실패하더라도 본 흐름의 예외를 덮어쓰지 않기 위해 best-effort로 동작한다.
     */
    public void clearSession(A2AClientRegistry.A2ARouteTarget target, String sessionId) {
        try {
            webClientBuilder.build()
                    .post()
                    .uri(clearEndpoint(target.endpoint()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(A2A_SESSION_HEADER, safeSessionId(sessionId))
                    .bodyValue(Map.of())
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block(target.timeout());
            logger.info("Downstream A2A session clear requested agentKey={}, endpoint={}", target.agentKey(), target.endpoint());
        } catch (RuntimeException ex) {
            logger.warn("Downstream A2A session clear failed agentKey={}, endpoint={}, error={}",
                    target.agentKey(), target.endpoint(), ex.getMessage());
        }
    }

    private String mergeSseChunks(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String[] lines = chunk.split("\\R");
            for (String rawLine : lines) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isBlank() || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    line = line.substring("data:".length()).trim();
                }
                if (line.isBlank() || DONE_TOKEN.equalsIgnoreCase(line)) {
                    continue;
                }
                merged.append(line);
                if (!line.endsWith("\n")) {
                    merged.append('\n');
                }
            }
        }
        return merged.toString().trim();
    }

    private boolean containsTerminalSignal(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }
        String normalized = chunk.trim();
        if (DONE_TOKEN.equalsIgnoreCase(normalized)) {
            return true;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("event: done")
                || lower.contains("data: " + DONE_TOKEN.toLowerCase())
                || lower.contains("\"reason\":\"completed\"")
                || lower.contains("\"reason\": \"completed\"")
                || lower.contains("\"reason\":\"timeout\"")
                || lower.contains("\"reason\": \"timeout\"")
                || lower.contains("\"reason\":\"canceled\"")
                || lower.contains("\"reason\": \"canceled\"");
    }

    private String clearEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return endpoint;
        }
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return normalized + "/clear";
    }

    private String safeSessionId(String sessionId) {
        return sessionId == null ? "" : sessionId;
    }
}
