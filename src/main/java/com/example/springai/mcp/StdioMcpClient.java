package com.example.springai.mcp;

import com.example.springai.exception.McpToolCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class StdioMcpClient implements McpClient {

    private static final Logger logger = LoggerFactory.getLogger(StdioMcpClient.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Process process;
    private final ObjectMapper objectMapper;
    private final BufferedReader stdoutReader;
    private final BufferedReader stderrReader;
    private final BufferedWriter stdinWriter;
    private final Object writeLock = new Object();
    private final AtomicLong requestId = new AtomicLong(1);
    private final AtomicReference<Map<String, Object>> toolsSchema = new AtomicReference<>(Map.of());
    private final Map<Long, CompletableFuture<Map<String, Object>>> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public StdioMcpClient(Process process, ObjectMapper objectMapper) throws IOException {
        this.process = process;
        this.objectMapper = objectMapper;
        this.stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        this.stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutPump();
        startErrorStreamDrainer();
        initialize();
    }

    @Override
    public String callTool(String toolName, Map<String, Object> params) {
        logger.info("Calling MCP tool: {}", toolName);
        try {
            Map<String, Object> requestParams = new HashMap<>();
            requestParams.put("name", toolName);
            requestParams.put("arguments", params != null ? params : Map.of());

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
            if (e instanceof McpToolCallException mcpToolCallException) {
                throw mcpToolCallException;
            }
            logger.error("MCP call failed for tool {}: {}", toolName, e.getMessage(), e);
            throw new McpToolCallException(toolName, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closed = true;
        completeAllPendingWithError(new IOException("MCP client closed"));

        try {
            stdinWriter.close();
            stdoutReader.close();
            stderrReader.close();
        } catch (IOException e) {
            logger.debug("Failed to close MCP client streams: {}", e.getMessage());
        }

        if (process.isAlive()) {
            try {
                process.destroy();
                boolean terminated = process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (!terminated && process.isAlive()) {
                    logger.warn("Process did not terminate gracefully, forcing shutdown");
                    process.destroyForcibly();
                    process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while closing MCP process", e);
                process.destroyForcibly();
            }
        }
    }

    @Override
    public boolean hasTool(String toolName) {
        for (Map<String, Object> tool : toolList()) {
            if (toolName.equals(tool.get("name"))) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> getToolsSchema() {
        return toolsSchema.get();
    }

    public String getFormattedToolsInfo() {
        StringBuilder formatted = new StringBuilder();
        for (Map<String, Object> tool : toolList()) {
            String name = (String) tool.get("name");
            String description = (String) tool.get("description");
            if (name == null || name.isBlank()) {
                continue;
            }
            formatted.append(name);
            if (description != null && !description.isBlank()) {
                formatted.append("(").append(description).append(")");
            }
            formatted.append(",");
        }
        return formatted.toString();
    }

    public String getToolInfo(String toolName) {
        for (Map<String, Object> tool : toolList()) {
            if (toolName.equals(tool.get("name"))) {
                return tool.toString();
            }
        }
        return "No schema available for tool: " + toolName;
    }

    private void initialize() throws IOException {
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
        loadToolsSchema();
        logger.info("MCP client initialized");
    }

    private void loadToolsSchema() {
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
            logger.warn("Failed to load tools schema: {}", e.getMessage());
            toolsSchema.set(Map.of());
        }
    }

    private Map<String, Object> sendRequest(String method, Object params) throws IOException {
        if (closed) {
            throw new IOException("MCP client is closed");
        }

        long id = requestId.getAndIncrement();
        CompletableFuture<Map<String, Object>> responseFuture = new CompletableFuture<>();
        pendingRequests.put(id, responseFuture);
        JsonRpcRequest request = new JsonRpcRequest(method, params, id);

        try {
            sendJsonLine(objectMapper.writeValueAsString(request));
            return waitForResponse(responseFuture);
        } finally {
            pendingRequests.remove(id);
        }
    }

    private Map<String, Object> waitForResponse(CompletableFuture<Map<String, Object>> responseFuture) throws IOException {
        try {
            return responseFuture.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("MCP response timeout after " + DEFAULT_TIMEOUT_SECONDS + " seconds", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for MCP response", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to read MCP response: " + cause.getMessage(), cause);
        }
    }

    private void sendNotification(String method, Object params) throws IOException {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        sendJsonLine(objectMapper.writeValueAsString(notification));
    }

    private void sendJsonLine(String payload) throws IOException {
        synchronized (writeLock) {
            stdinWriter.write(payload);
            stdinWriter.newLine();
            stdinWriter.flush();
        }
    }

    private void startStdoutPump() {
        Thread stdoutPump = new Thread(() -> {
            try {
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        if (!node.isObject()) {
                            // Ignore non-object JSON lines from tool stdout (e.g. plain strings/log fragments)
                            continue;
                        }
                        boolean looksLikeJsonRpc = node.has("jsonrpc") || node.has("id") || node.has("method");
                        if (!looksLikeJsonRpc) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = objectMapper.convertValue(node, Map.class);
                        routeMessage(message);
                    } catch (Exception parseError) {
                        // Ignore non-JSON-RPC stdout noise; keep the pump alive.
                        logger.debug("Ignoring non-MCP stdout line: {}", line);
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    completeAllPendingWithError(e);
                }
            } finally {
                if (!closed) {
                    completeAllPendingWithError(new IOException("MCP process stream closed"));
                }
            }
        }, "mcp-stdout-pump-" + process.pid());
        stdoutPump.setDaemon(true);
        stdoutPump.start();
    }

    private void startErrorStreamDrainer() {
        Thread errorDrainer = new Thread(() -> {
            try {
                String line;
                while ((line = stderrReader.readLine()) != null) {
                    if (!line.isBlank()) {
                        logger.warn("MCP stderr: {}", line);
                    }
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    logger.debug("Stopped reading MCP stderr: {}", e.getMessage());
                }
            }
        }, "mcp-stderr-drainer-" + process.pid());
        errorDrainer.setDaemon(true);
        errorDrainer.start();
    }

    private void routeMessage(Map<String, Object> message) {
        Object responseId = message.get("id");
        if (responseId == null) {
            return;
        }

        long id;
        if (responseId instanceof Number number) {
            id = number.longValue();
        } else {
            try {
                id = Long.parseLong(String.valueOf(responseId));
            } catch (NumberFormatException e) {
                logger.debug("Ignoring MCP response with non-numeric id: {}", responseId);
                return;
            }
        }

        CompletableFuture<Map<String, Object>> responseFuture = pendingRequests.remove(id);
        if (responseFuture != null) {
            responseFuture.complete(message);
        }
    }

    private void completeAllPendingWithError(Throwable error) {
        pendingRequests.forEach((id, future) -> future.completeExceptionally(error));
        pendingRequests.clear();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolList() {
        Object tools = toolsSchema.get().get("tools");
        if (tools instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }
}
