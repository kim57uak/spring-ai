package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.exception.DownstreamA2AException;
import com.example.springsupervisorai.service.agent.invoke.A2AClientRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class A2AJsonRpcClient {

    private final WebClient.Builder webClientBuilder;

    public A2AJsonRpcClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public JsonNode call(A2AClientRegistry.A2ARouteTarget target, JsonRpcRequest request) {
        try {
            return webClientBuilder.build()
                    .post()
                    .uri(target.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(target.timeout())
                    .onErrorMap(ex -> new DownstreamA2AException("Downstream A2A call failed: " + target.agentKey(), ex))
                    .blockOptional()
                    .orElseThrow(() -> new DownstreamA2AException("Empty downstream response: " + target.agentKey()));
        } catch (DownstreamA2AException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DownstreamA2AException("Downstream A2A call failed: " + target.agentKey(), ex);
        }
    }

    public String callStream(A2AClientRegistry.A2ARouteTarget target, JsonRpcRequest request) {
        try {
            List<String> chunks = webClientBuilder.build()
                    .post()
                    .uri(target.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .onErrorMap(ex -> new DownstreamA2AException("Downstream A2A stream failed: " + target.agentKey(), ex))
                    .collectList()
                    .block(target.timeout());
            return mergeSseChunks(chunks);
        } catch (DownstreamA2AException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DownstreamA2AException("Downstream A2A stream failed: " + target.agentKey(), ex);
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
                if (line.isBlank() || "[DONE]".equalsIgnoreCase(line)) {
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
}
