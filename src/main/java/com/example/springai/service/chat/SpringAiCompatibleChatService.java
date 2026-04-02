package com.example.springai.service.chat;

import com.example.springai.exception.ChatProcessingException;
import com.example.springai.service.chat.advisor.ChatAdvisorContextKeys;
import com.example.springai.service.llm.LlmCredentialValidator;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * OpenAI 호환 Chat Completions endpoint를 Spring AI로 호출하는 공통 베이스.
 */
public abstract class SpringAiCompatibleChatService implements SyncChatService, StreamChatService, StructuredChatService {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String providerName;
    private final LlmCallPolicy callPolicy;
    private final ChatClient chatClient;
    private final List<ToolCallbackProvider> toolCallbackProviders;
    private final ObservationRegistry observationRegistry;

    protected SpringAiCompatibleChatService(
            OpenAiChatModel baseOpenAiChatModel,
            LlmCallPolicy callPolicy,
            String providerName,
            String apiKey,
            String model,
            String completionEndpointUrl,
            int maxTokens,
            List<Advisor> advisors,
            List<ToolCallbackProvider> toolCallbackProviders,
            ObservationRegistry observationRegistry,
            String... envHints
    ) {
        this.providerName = providerName;
        this.callPolicy = callPolicy;
        this.toolCallbackProviders = toolCallbackProviders;
        this.observationRegistry = observationRegistry;
        LlmCredentialValidator.requireValidApiKey(providerName, apiKey, envHints);
        this.chatClient = createChatClient(
                baseOpenAiChatModel,
                apiKey,
                model,
                completionEndpointUrl,
                maxTokens,
                advisors,
                toolCallbackProviders
        );
    }

    @Override
    public String generate(String message) {
        return generate(message, ChatRequestContext.empty());
    }

    @Override
    public String generate(String message, ChatRequestContext context) {
        Observation observation = startObservation("sync");
        return callPolicy.executeSync(providerName, () -> {
            try {
                String content = request(message, context)
                        .call()
                        .content();
                if (content == null) {
                    throw new ChatProcessingException(providerName + " returned empty response body");
                }
                return content;
            } catch (RuntimeException e) {
                observation.error(e);
                throw e;
            } finally {
                observation.stop();
            }
        });
    }

    @Override
    public Flux<String> streamGenerate(String message) {
        return streamGenerate(message, ChatRequestContext.empty());
    }

    @Override
    public Flux<String> streamGenerate(String message, ChatRequestContext context) {
        callPolicy.acquireBeforeStream();
        Observation observation = startObservation("stream");
        return request(message, context)
                .stream()
                .content()
                .retryWhen(callPolicy.streamRetrySpec(providerName))
                .onErrorMap(
                        WebClientResponseException.class,
                        e -> callPolicy.toChatProcessingException(providerName, e)
                )
                .onErrorMap(e -> !(e instanceof ChatProcessingException), this::toUnexpectedChatProcessingException)
                .doOnError(e -> {
                    observation.error(e);
                    logger.error("{} stream error", providerName, e);
                })
                .doFinally(signal -> observation.stop());
    }

    @Override
    public <T> T generateStructured(String message, Class<T> type) {
        return generateStructured(message, type, ChatRequestContext.empty());
    }

    @Override
    public <T> T generateStructured(String message, Class<T> type, ChatRequestContext context) {
        Observation observation = startObservation("structured");
        return callPolicy.executeSync(providerName, () -> {
            try {
                return request(message, context)
                        .call()
                        .entity(type);
            } catch (RuntimeException e) {
                observation.error(e);
                throw e;
            } finally {
                observation.stop();
            }
        });
    }

    private ChatClient.ChatClientRequestSpec request(String message, ChatRequestContext context) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(message);
        String modelOverride = resolveModelOverride(context);
        if (modelOverride != null) {
            spec = spec.options(OpenAiChatOptions.builder().model(modelOverride).build());
        }
        spec = spec.advisors(advisor -> {
            advisor.param(ChatAdvisorContextKeys.PROVIDER, providerName);
            if (context != null && context.hasSessionId()) {
                advisor.param(ChatAdvisorContextKeys.SESSION_ID, context.sessionId());
            }
        });
        if (context != null && context.mcpToolCallbacksEnabled() && toolCallbackProviders != null && !toolCallbackProviders.isEmpty()) {
            spec = spec.toolCallbacks(toolCallbackProviders.toArray(ToolCallbackProvider[]::new));
        }
        return spec;
    }

    private String resolveModelOverride(ChatRequestContext context) {
        if (context == null || !context.hasRequestedModel()) {
            return null;
        }
        String requested = context.requestedModel().trim();
        if (requested.isBlank()) {
            return null;
        }
        String normalized = requested.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mistral-large" -> "mistral-large-latest";
            case "mistral-medium" -> "mistral-medium-latest";
            case "mistral", "openai", "gemini", "gemini-lite" -> null;
            default -> requested;
        };
    }

    private Observation startObservation(String mode) {
        return Observation.start("springai.chat.call", observationRegistry)
                .lowCardinalityKeyValue("provider", providerName)
                .lowCardinalityKeyValue("mode", mode);
    }

    private ChatClient createChatClient(
            OpenAiChatModel baseOpenAiChatModel,
            String apiKey,
            String model,
            String completionEndpointUrl,
            int maxTokens,
            List<Advisor> advisors,
            List<ToolCallbackProvider> toolCallbackProviders
    ) {
        EndpointConfig endpoint = EndpointConfig.from(completionEndpointUrl);
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(endpoint.baseUrl())
                .completionsPath(endpoint.completionsPath())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .build();

        OpenAiChatModel chatModel = baseOpenAiChatModel.mutate()
                .openAiApi(api)
                .defaultOptions(options)
                .build();

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (advisors != null && !advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        return builder.build();
    }

    private ChatProcessingException toUnexpectedChatProcessingException(Throwable throwable) {
        return new ChatProcessingException("API call failed: " + throwable.getMessage(), throwable);
    }

    private record EndpointConfig(String baseUrl, String completionsPath) {
        static EndpointConfig from(String endpointUrl) {
            if (endpointUrl == null || endpointUrl.isBlank()) {
                throw new IllegalArgumentException("completion endpoint URL must not be blank");
            }
            URI uri = URI.create(endpointUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                throw new IllegalArgumentException("invalid completion endpoint URL: " + endpointUrl);
            }

            String base = URI.create("%s://%s%s".formatted(
                    scheme,
                    host,
                    uri.getPort() > -1 ? ":" + uri.getPort() : ""
            )).toString();
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = "/v1/chat/completions";
            }
            return new EndpointConfig(base, path);
        }
    }
}
