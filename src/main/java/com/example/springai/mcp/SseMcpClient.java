package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpToolCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP(SSE) MCP 클라이언트.
 * <p>
 * 동작 방식:
 * - 요청은 JSON-RPC over HTTP POST로 전송한다.
 * - 필요 시 SSE 채널로 비동기 응답을 수신한다.
 * - transport 설정값 `sse`를 기준으로 선택된다.
 */
public class SseMcpClient implements McpClient {

    private static final Logger logger = LoggerFactory.getLogger(SseMcpClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final URI sseEndpointUri;
    private final URI fallbackRpcEndpointUri;
    private final AtomicLong requestId = new AtomicLong(1);
    private final AtomicReference<Map<String, Object>> toolsSchema = new AtomicReference<>(Map.of());
    private final Map<Long, CompletableFuture<Map<String, Object>>> pendingResponses = new ConcurrentHashMap<>();
    private volatile URI messageEndpointUri;
    private volatile boolean initialized;
    private volatile InputStream sseInputStream;
    private volatile Thread sseReaderThread;

    public SseMcpClient(McpProperties.ServerConfig config, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofMillis(Math.max(1_000, config.getTimeoutMs()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
        this.sseEndpointUri = buildSseEndpoint(config);
        this.fallbackRpcEndpointUri = buildFallbackRpcEndpoint(config);
        initialize();
    }

    @Override
    public String callTool(String toolName, Map<String, Object> params) {
        ensureInitialized();
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("name", toolName);
        requestParams.put("arguments", params == null ? Map.of() : params);
        try {
            Map<String, Object> response = sendRequest("tools/call", requestParams);
            if (response.containsKey("error")) {
                throw new McpToolCallException(toolName, String.valueOf(response.get("error")));
            }
            Object result = response.get("result");
            if (result == null) {
                throw new McpToolCallException(toolName, "Response did not contain result");
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            if (e instanceof McpToolCallException toolCallException) {
                throw toolCallException;
            }
            throw new McpToolCallException(toolName, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        initialized = false;
        closeSseSession();
    }

    @Override
    public boolean hasTool(String toolName) {
        for (Map<String, Object> tool : listTools()) {
            if (toolName.equals(tool.get("name"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Map<String, Object>> listTools() {
        ensureInitialized();
        refreshToolsSchema();
        Object tools = toolsSchema.get().get("tools");
        if (!(tools instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> converted = new HashMap<>();
                rawMap.forEach((k, v) -> converted.put(String.valueOf(k), v));
                mapped.add(converted);
            }
        }
        return mapped;
    }

    /**
     * SSE MCP 초기화 시퀀스를 수행한다.
     * <p>
     * 처리 순서:
     * - SSE 세션을 연다.
     * - initialize 요청을 전송한다.
     * - initialized 알림을 보낸다.
     */
    private void initialize() {
        try {
            // SSE 세션 수립 후 initialize/initialized 핸드셰이크를 수행한다.
            openSseSession();
            Map<String, Object> initParams = new HashMap<>();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.put("clientInfo", Map.of("name", "spring-ai", "version", "2.0.0"));
            initParams.put("capabilities", Map.of(
                    "roots", Map.of("listChanged", false),
                    "sampling", Map.of(),
                    "tools", Map.of("listChanged", false)
            ));
            sendRequest("initialize", initParams);
            sendNotification("notifications/initialized", Map.of());
            initialized = true;
        } catch (Exception e) {
            initialized = false;
            logger.warn("SSE MCP initialize failed endpoint={} message={}", sseEndpointUri, e.getMessage());
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private void refreshToolsSchema() {
        try {
            Map<String, Object> response = sendRequest("tools/list", Map.of());
            Object result = response.get("result");
            if (result instanceof Map<?, ?> mapResult) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) mapResult;
                toolsSchema.set(typed);
            } else {
                toolsSchema.set(Map.of());
            }
        } catch (Exception e) {
            logger.warn("Failed to refresh SSE MCP tools endpoint={} message={}", activePostEndpoint(), e.getMessage());
        }
    }

    private void sendNotification(String method, Object params) throws IOException, InterruptedException {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        String payload = objectMapper.writeValueAsString(notification);
        HttpRequest request = HttpRequest.newBuilder(activePostEndpoint())
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * JSON-RPC 요청을 전송하고 응답을 기다린다.
     * <p>
     * 처리 전략:
     * - HTTP 동기 응답을 우선 사용한다.
     * - 즉시 응답이 불충분하면 SSE 비동기 응답으로 폴백한다.
     */
    private Map<String, Object> sendRequest(String method, Object params) throws IOException, InterruptedException {
        long id = requestId.getAndIncrement();
        CompletableFuture<Map<String, Object>> responseFuture = new CompletableFuture<>();
        pendingResponses.put(id, responseFuture);
        Map<String, Object> payload = new HashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);

        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(activePostEndpoint())
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 299) {
                throw new IOException("MCP HTTP error: " + response.statusCode());
            }

            String responseBody = response.body();
            if (responseBody != null && !responseBody.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = objectMapper.readValue(responseBody, Map.class);
                Long responseId = readResponseId(message);
                if (responseId != null && responseId == id) {
                    return message;
                }
                if (message.containsKey("result") || message.containsKey("error")) {
                    return message;
                }
            }

            return responseFuture.get(requestTimeout.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("request timed out", e);
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        } finally {
            pendingResponses.remove(id);
        }
    }

    private URI buildSseEndpoint(McpProperties.ServerConfig config) {
        String host = requiredHost(config);
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "/sse";
        }
        String normalizedHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return URI.create(normalizedHost + normalizedEndpoint);
    }

    private URI buildFallbackRpcEndpoint(McpProperties.ServerConfig config) {
        String host = requiredHost(config);
        String normalizedHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        return URI.create(normalizedHost + "/mcp");
    }

    private String requiredHost(McpProperties.ServerConfig config) {
        String host = config.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SSE MCP host is required");
        }
        return host;
    }

    /**
     * SSE 세션을 열고 message endpoint를 결정한다.
     * <p>
     * endpoint 공지 이벤트가 없으면 fallback RPC endpoint를 사용한다.
     */
    private void openSseSession() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(sseEndpointUri)
                .timeout(requestTimeout)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("SSE handshake HTTP error: " + response.statusCode());
        }
        sseInputStream = response.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(sseInputStream, StandardCharsets.UTF_8));

        String endpointPath = readEndpointPath(reader);
        if (endpointPath != null && !endpointPath.isBlank()) {
            messageEndpointUri = resolveMessageEndpoint(endpointPath);
            logger.info("SSE MCP message endpoint resolved: {}", messageEndpointUri);
        } else {
            messageEndpointUri = fallbackRpcEndpointUri;
            logger.warn("SSE MCP endpoint event missing; fallback endpoint={}", messageEndpointUri);
        }

        // 세션 바인딩 채널이 필요한 서버를 위해 SSE 스트림을 지속 유지한다.
        sseReaderThread = new Thread(() -> drainSse(reader), "mcp-sse-reader");
        sseReaderThread.setDaemon(true);
        sseReaderThread.start();
    }

    private String readEndpointPath(BufferedReader reader) throws IOException {
        String line;
        long deadline = System.currentTimeMillis() + requestTimeout.toMillis();
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).trim();
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
        }
        return "";
    }

    private URI resolveMessageEndpoint(String endpointPath) {
        if (endpointPath.startsWith("http://") || endpointPath.startsWith("https://")) {
            return URI.create(endpointPath);
        }
        String host = sseEndpointUri.toString();
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        String path = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
        int pathStart = base.indexOf("/", base.indexOf("://") + 3);
        if (pathStart > -1) {
            base = base.substring(0, pathStart);
        }
        return URI.create(base + path);
    }

    private URI activePostEndpoint() {
        return messageEndpointUri == null ? fallbackRpcEndpointUri : messageEndpointUri;
    }

    /**
     * SSE 수신 루프.
     * <p>
     * 빈 줄 단위 이벤트 블록을 경계로 data 라인을 병합해 dispatch 한다.
     */
    private void drainSse(BufferedReader reader) {
        try {
            StringBuilder dataBuffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring("data:".length()).trim());
                    continue;
                }
                if (line.isBlank()) {
                    dispatchSseData(dataBuffer.toString());
                    dataBuffer.setLength(0);
                }
            }
            dispatchSseData(dataBuffer.toString());
        } catch (IOException e) {
            if (initialized) {
                logger.warn("SSE channel closed: {}", e.getMessage());
            }
        }
    }

    /**
     * SSE data payload를 파싱해 pending 응답 future로 라우팅한다.
     */
    private void dispatchSseData(String data) {
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = objectMapper.readValue(data, Map.class);
            Long id = readResponseId(message);
            if (id == null) {
                return;
            }
            CompletableFuture<Map<String, Object>> pending = pendingResponses.get(id);
            if (pending != null) {
                pending.complete(message);
            }
        } catch (Exception ignored) {
            // endpoint 공지 및 비 JSON 데이터가 섞일 수 있어 무시한다.
        }
    }

    private Long readResponseId(Map<String, Object> message) {
        Object rawId = message.get("id");
        if (rawId instanceof Number number) {
            return number.longValue();
        }
        if (rawId != null) {
            try {
                return Long.parseLong(String.valueOf(rawId));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void closeSseSession() {
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
            sseReaderThread = null;
        }
        if (sseInputStream != null) {
            try {
                sseInputStream.close();
            } catch (IOException ignored) {
                // no-op
            }
            sseInputStream = null;
        }
    }
}
