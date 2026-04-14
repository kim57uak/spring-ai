package com.example.springsupervisorai.service.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReflectionSupervisorChatGatewayTest {

    @Test
    void completeDelegatesThroughReflectionBridge() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("modelChatServiceFactory", FakeModelChatServiceFactory.class, FakeModelChatServiceFactory::new);
            context.refresh();
            ReflectionSupervisorChatGateway gateway = new ReflectionSupervisorChatGateway(context);

            String response = gateway.complete("hello", "openai", "s-1");

            assertThat(response).isEqualTo("sync:OPENAI:hello:s-1");
        }
    }

    @Test
    void streamDelegatesThroughReflectionBridge() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("modelChatServiceFactory", FakeModelChatServiceFactory.class, FakeModelChatServiceFactory::new);
            context.refresh();
            ReflectionSupervisorChatGateway gateway = new ReflectionSupervisorChatGateway(context);

            List<String> response = gateway.stream("hello", "gemini-2.5-flash-lite", "s-2")
                    .collectList()
                    .block();

            assertThat(response).containsExactly("stream:GEMINI_LITE:hello:s-2");
        }
    }

    @Test
    void completeShouldThrowWhenModelIsUnsupported() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("modelChatServiceFactory", FakeModelChatServiceFactory.class, FakeModelChatServiceFactory::new);
            context.refresh();
            ReflectionSupervisorChatGateway gateway = new ReflectionSupervisorChatGateway(context);

            assertThatThrownBy(() -> gateway.complete("hello", "unknown-model", "s-3"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported model");
        }
    }

    private enum ModelType {
        OPENAI,
        GEMINI,
        GEMINI_LITE,
        MISTRAL
    }

    private static final class FakeModelChatServiceFactory {
        public FakeSyncChatService resolveSync(ModelType modelType) {
            return new FakeSyncChatService(modelType);
        }

        public FakeStreamChatService resolveStream(ModelType modelType) {
            return new FakeStreamChatService(modelType);
        }
    }

    private record FakeContext(String sessionId, String model) {
        public static FakeContext of(String sessionId, boolean ignored, String model) {
            return new FakeContext(sessionId, model);
        }
    }

    private record FakeSyncChatService(ModelType modelType) {
        public String generate(String prompt, FakeContext context) {
            return "sync:" + modelType + ":" + prompt + ":" + context.sessionId();
        }
    }

    private record FakeStreamChatService(ModelType modelType) {
        public Flux<String> streamGenerate(String prompt, FakeContext context) {
            return Flux.just("stream:" + modelType + ":" + prompt + ":" + context.sessionId());
        }
    }
}
