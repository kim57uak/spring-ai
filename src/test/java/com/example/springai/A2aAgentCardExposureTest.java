package com.example.springai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agent.cards.enabled-scopes[0]=product",
                "agent.cards.enabled-scopes[1]=reservation"
        }
)
class A2aAgentCardExposureTest {

    @LocalServerPort
    private int port;

    private final WebClient webClient = WebClient.builder().build();

    @Test
    void globalAgentCardReturnsOnlyEnabledScopes() {
        JsonNode response = webClient.get()
                .uri("http://localhost:" + port + "/.well-known/agent.json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.isArray()).isTrue();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.toString()).contains("Product Agent");
        assertThat(response.toString()).contains("Reservation Agent");
        assertThat(response.toString()).doesNotContain("Search Agent");
    }

    @Test
    void scopedAgentCardReturnsSingleCardAndHidesUnselectedAgent() {
        JsonNode productCard = webClient.get()
                .uri("http://localhost:" + port + "/a2a/product/.well-known/agent.json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        assertThat(productCard).isNotNull();
        assertThat(productCard.toString()).contains("Product Agent");
        assertThat(productCard.toString()).doesNotContain("Reservation Agent");
        assertThat(productCard.toString()).doesNotContain("Search Agent");

        Integer searchCardStatus = webClient.get()
                .uri("http://localhost:" + port + "/a2a/search/.well-known/agent.json")
                .exchangeToMono(response -> Mono.just(response.statusCode().value()))
                .block();

        assertThat(searchCardStatus).isEqualTo(404);
    }

    @Test
    void disabledScopeA2aRequestIsRejected() {
        JsonNode response = postJsonRpc("/a2a/search", Map.of(
                "jsonrpc", "2.0",
                "id", "disabled-scope",
                "method", "tasks/list",
                "params", Map.of("limit", 10)
        ));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32004);
        assertThat(response.path("error").path("message").asText()).contains("Scope is not enabled");
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

