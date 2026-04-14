package com.example.springai;

import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpChatApiTest {

    @LocalServerPort
    private int port;

    private final WebClient webClient = WebClient.builder().build();

    @Test
    void statusEndpointReturnsSessionInfo() {
        ChatResponse response = webClient.get()
                .uri(baseUrl() + "/status")
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.response()).contains("Session:");
        assertThat(response.response()).contains("Messages:");
    }

    @Test
    void clearEndpointReturnsNoError() {
        HttpStatus status = webClient.post()
                .uri(baseUrl() + "/clear")
                .retrieve()
                .toBodilessEntity()
                .blockOptional()
                .map(entity -> HttpStatus.valueOf(entity.getStatusCode().value()))
                .orElse(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(status).isEqualTo(HttpStatus.OK);
    }

    @Test
    void blankMessageReturnsBadRequest() {
        assertThatThrownBy(() -> webClient.post()
                .uri(baseUrl() + "/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatRequest("   ", "mistral"))
                .retrieve()
                .bodyToMono(String.class)
                .block())
                .isInstanceOf(WebClientResponseException.BadRequest.class)
                .satisfies(ex -> {
                    WebClientResponseException.BadRequest badRequest =
                            (WebClientResponseException.BadRequest) ex;
                    assertThat(badRequest.getResponseBodyAsString()).contains("must not be blank");
                });
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/http-chat";
    }
}
