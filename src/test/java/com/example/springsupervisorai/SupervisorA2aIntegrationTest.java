package com.example.springsupervisorai;

import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@TestPropertySource(properties = {
        "spring.redis.enabled=false"
})
@SpringBootTest(
        classes = SupervisorTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.output.ansi.enabled=never"
        }
)
class SupervisorA2aIntegrationTest {

    private static final List<String> DOWNSTREAM_BODIES = new CopyOnWriteArrayList<>();
    private static final HttpServer DOWNSTREAM_SERVER = createServer();

    @LocalServerPort
    private int port;

    @MockitoBean
    private SupervisorLlmRuntime supervisorLlmRuntime;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private RLock lock;

    private final WebClient webClient = WebClient.builder().build();

    @DynamicPropertySource
    static void overrideRoutingEndpoints(DynamicPropertyRegistry registry) {
        String endpointBase = "http://localhost:" + DOWNSTREAM_SERVER.getAddress().getPort();
        registry.add("host.a2a.routing.product.endpoint", () -> endpointBase + "/a2a/product");
        registry.add("host.a2a.routing.reservation.endpoint", () -> endpointBase + "/a2a/reservation");
        registry.add("host.a2a.routing.search.endpoint", () -> endpointBase + "/a2a/search");
        registry.add("host.a2a.retry.max-retries", () -> 0);
        registry.add("host.a2a.stream.timeout-ms", () -> 100);
    }

    @BeforeEach
    void setUp() {
        DOWNSTREAM_BODIES.clear();
        when(supervisorLlmRuntime.stream(anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("최종 응답"));
        when(supervisorLlmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"complete":false,"plans":[{"agentKey":"product","method":"message/send","reason":"상품 요청", "priority":1, "arguments":{}}]}
                        """.trim());
    }

    @AfterAll
    static void tearDown() {
        DOWNSTREAM_SERVER.stop(0);
    }

    @Test
    void messageSendRoutesToDownstreamAndReturnsTaskView() {
        JsonNode response = postJsonRpc(Map.of(
                "jsonrpc", "2.0",
                "id", "sup-send-1",
                "method", "message/send",
                "params", Map.of("messageText", "상품 가격 알려줘", "model", "openai")
        ));

        assertThat(response.path("error").isMissingNode() || response.path("error").isNull()).isTrue();
        assertThat(response.path("result").path("id").asText()).startsWith("sup-task-");
        assertThat(response.path("result").path("status").asText()).isIn("WORKING", "COMPLETED", "FAILED");
    }

    @Test
    void tasksListReturnsArrayEnvelope() {
        JsonNode response = postJsonRpc(Map.of(
                "jsonrpc", "2.0",
                "id", "sup-list-1",
                "method", "tasks/list",
                "params", Map.of("limit", 10)
        ));

        assertThat(response.path("error").isMissingNode() || response.path("error").isNull()).isTrue();
        assertThat(response.path("result").path("tasks").isArray()).isTrue();
    }

    @Test
    void streamWithBlankMessageReturnsInvalidParamsErrorChunk() {
        String sseBody = webClient.post()
                .uri(baseUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "sup-stream-bad-1",
                        "method", "message/stream",
                        "params", Map.of("messageText", "   ", "model", "openai")
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertThat(sseBody).isNotNull();
        assertThat(sseBody).contains("\"code\":-32602");
        assertThat(sseBody).contains("messageText is required");
        assertThat(sseBody).contains("event: error");
    }

    @Test
    void tasksListWithInvalidLimitReturnsInvalidParams() {
        JsonNode response = postJsonRpc(Map.of(
                "jsonrpc", "2.0",
                "id", "sup-list-bad-1",
                "method", "tasks/list",
                "params", Map.of("limit", 500)
        ));

        assertThat(response.path("error").isObject()).isTrue();
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText()).contains("limit must be between 1 and 200");
    }

    @Test
    void tasksReviewDecideStreamShouldHandleReviseDecision() {
        // given
        JsonNode sendResponse = postJsonRpc(Map.of(
                "jsonrpc", "2.0",
                "id", "sup-send-1",
                "method", "message/send",
                "params", Map.of("messageText", "상품 가격 알려줘", "model", "openai")
        ));
        String taskId = sendResponse.path("result").path("id").asText();

        // when
        String sseBody = webClient.post()
                .uri(baseUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "sup-review-decide-1",
                        "method", "tasks/review/decide/stream",
                        "params", Map.of(
                                "id", taskId,
                                "decision", "REVISE",
                                "reason", "Revised by user",
                                "decisionId", "dec-1",
                                "revisedMessage", "상품 가격과 재고 알려줘"
                        )
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // then
        assertThat(sseBody).isNotNull();
        assertThat(sseBody).contains("event: chunk");
        assertThat(sseBody).contains("revised response");
    }

    @Test
    void tasksReviewDecideStreamShouldHandleReviseDecisionWithEmptyMessage() {
        // given
        JsonNode sendResponse = postJsonRpc(Map.of(
                "jsonrpc", "2.0",
                "id", "sup-send-1",
                "method", "message/send",
                "params", Map.of("messageText", "상품 가격 알려줘", "model", "openai")
        ));
        String taskId = sendResponse.path("result").path("id").asText();

        // when
        String sseBody = webClient.post()
                .uri(baseUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "sup-review-decide-2",
                        "method", "tasks/review/decide/stream",
                        "params", Map.of(
                                "id", taskId,
                                "decision", "REVISE",
                                "reason", "Revised by user",
                                "decisionId", "dec-2",
                                "revisedMessage", ""
                        )
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // then
        assertThat(sseBody).isNotNull();
        assertThat(sseBody).contains("event: chunk");
    }

    private JsonNode postJsonRpc(Map<String, Object> payload) {
        return webClient.post()
                .uri(baseUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/a2a/supervisor";
    }

    private static HttpServer createServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/a2a/product", SupervisorA2aIntegrationTest::handleDownstream);
            server.createContext("/a2a/reservation", SupervisorA2aIntegrationTest::handleDownstream);
            server.createContext("/a2a/search", SupervisorA2aIntegrationTest::handleDownstream);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start downstream mock server", ex);
        }
    }

    private static void handleDownstream(HttpExchange exchange) throws IOException {
        String body = readBody(exchange.getRequestBody());
        DOWNSTREAM_BODIES.add(body);
        byte[] response = """
                {"jsonrpc":"2.0","id":"mock-1","result":{"id":"downstream-task-1","status":"COMPLETED","response":"ok"}}
                """.trim().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
