package com.example.springai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.output.ansi.enabled=never",
                "logging.level.com.example.springai.mcp.StdioMcpClient=ERROR"
        }
)
class A2aApiTest {

    @LocalServerPort
    private int port;

    private final WebClient webClient = WebClient.builder().build();

    @Test
    void agentCardEndpointReturnsRegisteredCards() {
        JsonNode response = webClient.get()
                .uri("http://localhost:" + port + "/.well-known/agent.json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.isArray()).isTrue();
        assertThat(response.size()).isGreaterThanOrEqualTo(3);
        assertThat(response.toString()).contains("Product Agent");
        assertThat(response.toString()).contains("Reservation Agent");
        assertThat(response.toString()).contains("Search Agent");
    }

    @Test
    void invalidJsonRpcVersionReturnsError() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "1.0",
                "id", "bad-version",
                "method", "tasks/list",
                "params", Map.of("limit", 10)
        ));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(response.path("error").path("message").asText()).contains("Invalid JSON-RPC request");
    }

    @Test
    void tasksListReturnsEmptyAtStart() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "2.0",
                "id", "list-1",
                "method", "tasks/list",
                "params", Map.of("limit", 10)
        ));

        assertThat(response.path("error").isMissingNode() || response.path("error").isNull()).isTrue();
        assertThat(response.path("result").path("tasks").isArray()).isTrue();
        assertThat(response.path("result").path("tasks").size()).isEqualTo(0);
    }

    @Test
    void listTasksPascalCaseIsAlsoAccepted() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "2.0",
                "id", "list-pascal-1",
                "method", "ListTasks",
                "params", Map.of("limit", 10)
        ));

        assertThat(response.path("error").isMissingNode() || response.path("error").isNull()).isTrue();
        assertThat(response.path("result").path("tasks").isArray()).isTrue();
    }

    @Test
    void messageSendWithBlankTextReturnsInvalidParams() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "2.0",
                "id", "send-blank",
                "method", "message/send",
                "params", Map.of("messageText", "   ", "model", "openai")
        ));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText()).contains("messageText is required");
    }

    @Test
    void sendMessagePascalCaseWithMessagePartsIsAccepted() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "2.0",
                "id", "send-pascal",
                "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of(
                                "role", "user",
                                "parts", java.util.List.of(Map.of("type", "text", "text", "   "))
                        ),
                        "model", "openai"
                )
        ));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText()).contains("messageText is required");
    }

    @Test
    void tasksGetWithoutIdReturnsInvalidParams() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "2.0",
                "id", "get-bad",
                "method", "tasks/get",
                "params", Map.of()
        ));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText()).contains("id is required");
    }

    @Test
    void tasksCancelWithoutIdReturnsInvalidParams() {
        JsonNode response = postJsonRpc("/a2a/product", Map.of(
                "jsonrpc", "2.0",
                "id", "cancel-bad",
                "method", "tasks/cancel",
                "params", Map.of("reason", "test")
        ));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText()).contains("id is required");
    }

    @Test
    void malformedA2aRequestReturnsJsonRpcErrorEnvelope() {
        JsonNode response = webClient.post()
                .uri("http://localhost:" + port + "/a2a/product")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not-valid-json")
                .exchangeToMono(clientResponse -> clientResponse.bodyToMono(JsonNode.class))
                .block();

        assertThat(response).isNotNull();
        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(response.path("error").path("message").asText()).contains("Invalid JSON-RPC request");
    }

    @Test
    void messageStreamIsServedFromMainA2aEndpointByMethod() {
        String sseBody = webClient.post()
                .uri("http://localhost:" + port + "/a2a/product")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "stream-bad-param",
                        "method", "message/stream",
                        "params", Map.of("messageText", "   ", "model", "openai")
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertThat(sseBody).isNotNull();
        assertThat(sseBody).contains("\"code\":-32602");
        assertThat(sseBody).contains("messageText is required");
    }

    @Test
    void sendStreamingMessagePascalCaseIsServedFromMainA2aEndpointByMethod() {
        String sseBody = webClient.post()
                .uri("http://localhost:" + port + "/a2a/product")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "stream-pascal-bad-param",
                        "method", "SendStreamingMessage",
                        "params", Map.of(
                                "message", Map.of(
                                        "role", "user",
                                        "parts", java.util.List.of(Map.of("type", "text", "text", "   "))
                                )
                        )
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertThat(sseBody).isNotNull();
        assertThat(sseBody).contains("\"code\":-32602");
    }

    @Test
    void streamAliasRejectsNonStreamingMethods() {
        String sseBody = webClient.post()
                .uri("http://localhost:" + port + "/a2a/product/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "stream-alias-method-check",
                        "method", "tasks/list",
                        "params", Map.of("limit", 5)
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertThat(sseBody).isNotNull();
        assertThat(sseBody).contains("\"code\":-32601");
    }

    private JsonNode postJsonRpc(String path, Map<String, Object> payload) {
        return webClient.post()
                .uri("http://localhost:" + port + path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }
}
