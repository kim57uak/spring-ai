package com.example.springsupervisorai.service.agent.runtime;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Locale;

/**
 * 현재 단일 런타임에서 기존 ChatService 빈을 호출하기 위한 리플렉션 어댑터.
 * <p>
 * 앱 분리 시에는 본 어댑터를 별도 구현으로 대체할 수 있다.
 */
@Component
public class ReflectionSupervisorChatGateway implements SupervisorChatGateway {

    private static final String FACTORY_BEAN = "modelChatServiceFactory";
    private static final String METHOD_RESOLVE_SYNC = "resolveSync";
    private static final String METHOD_RESOLVE_STREAM = "resolveStream";
    private static final String METHOD_GENERATE = "generate";
    private static final String METHOD_STREAM_GENERATE = "streamGenerate";
    private static final String METHOD_CONTEXT_OF = "of";
    private static final Map<String, String> DIRECT_MODEL_MAPPING = Map.of(
            "openai", "OPENAI",
            "gemini", "GEMINI",
            "gemini-lite", "GEMINI_LITE",
            "mistral", "MISTRAL"
    );

    private final ApplicationContext applicationContext;
    private volatile FactoryBridge factoryBridge;

    /**
     * @param applicationContext 런타임 빈 조회용 컨텍스트
     */
    public ReflectionSupervisorChatGateway(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 리플렉션으로 sync ChatService를 해석하고 `generate`를 호출한다.
     */
    @Override
    public String complete(String prompt, String model, String sessionId) {
        Object chatService = resolveSyncChatService(model);
        Object context = buildContext(chatService, METHOD_GENERATE, sessionId, model);
        return invokeSync(chatService, METHOD_GENERATE, prompt, context);
    }

    /**
     * 리플렉션으로 stream ChatService를 해석하고 `streamGenerate`를 호출한다.
     */
    @Override
    public Flux<String> stream(String prompt, String model, String sessionId) {
        Object chatService = resolveStreamChatService(model);
        Object context = buildContext(chatService, METHOD_STREAM_GENERATE, sessionId, model);
        return invokeStream(chatService, METHOD_STREAM_GENERATE, prompt, context);
    }

    /**
     * sync 전용 ChatService를 조회한다.
     */
    private Object resolveSyncChatService(String model) {
        return resolveChatService(model, bridge().resolveSync());
    }

    /**
     * stream 전용 ChatService를 조회한다.
     */
    private Object resolveStreamChatService(String model) {
        return resolveChatService(model, bridge().resolveStream());
    }

    /**
     * resolver 메서드와 모델명을 이용해 ChatService 빈을 조회한다.
     */
    private Object resolveChatService(String model, Method resolver) {
        try {
            Class<?> modelTypeClass = resolver.getParameterTypes()[0];
            Enum<?> modelType = toEnumConstant(modelTypeClass, mapModelType(model));
            return resolver.invoke(bridge().factory(), modelType);
        } catch (InvocationTargetException ex) {
            throw unwrap(ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to resolve chat service bridge", ex);
        }
    }

    /**
     * ChatService 호출용 request context 인스턴스를 생성한다.
     */
    private Object buildContext(Object chatService, String methodName, String sessionId, String model) {
        try {
            Method target = findMessageMethod(chatService.getClass(), methodName);
            Class<?> contextType = target.getParameterTypes()[1];
            Method contextFactory = contextType.getMethod(METHOD_CONTEXT_OF, String.class, boolean.class, String.class);
            return contextFactory.invoke(null, sessionId, false, model);
        } catch (InvocationTargetException ex) {
            throw unwrap(ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to build chat request context bridge", ex);
        }
    }

    /**
     * sync 메서드를 호출해 문자열 응답을 반환한다.
     */
    private String invokeSync(Object chatService, String methodName, String prompt, Object context) {
        try {
            Method method = findMessageMethod(chatService.getClass(), methodName);
            Object value = method.invoke(chatService, prompt, context);
            return value == null ? null : value.toString();
        } catch (InvocationTargetException ex) {
            throw unwrap(ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to invoke sync chat bridge", ex);
        }
    }

    /**
     * stream 메서드를 호출해 문자열 Flux로 변환한다.
     */
    private Flux<String> invokeStream(Object chatService, String methodName, String prompt, Object context) {
        try {
            Method method = findMessageMethod(chatService.getClass(), methodName);
            Object value = method.invoke(chatService, prompt, context);
            if (value instanceof Flux<?> flux) {
                return flux.map(item -> item == null ? "" : item.toString());
            }
            throw new IllegalStateException("Bridge stream result is not Flux");
        } catch (InvocationTargetException ex) {
            throw unwrap(ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to invoke stream chat bridge", ex);
        }
    }

    /**
     * 메시지 처리 메서드(generate/streamGenerate)를 탐색한다.
     */
    private Method findMessageMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(name))
                .filter(method -> method.getParameterCount() == 2)
                .filter(method -> method.getParameterTypes()[0].equals(String.class))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Bridge method not found: " + type.getName() + "#" + name));
    }

    /**
     * 이름/인자 수로 일반 메서드를 탐색한다.
     */
    private Method findMethod(Class<?> type, String name, int argCount) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(name))
                .filter(method -> method.getParameterCount() == argCount)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Bridge method not found: " + type.getName() + "#" + name));
    }

    /**
     * modelChatServiceFactory 브리지 메타데이터를 지연 초기화한다.
     */
    private FactoryBridge bridge() {
        FactoryBridge current = factoryBridge;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (factoryBridge == null) {
                factoryBridge = initFactoryBridge();
            }
            return factoryBridge;
        }
    }

    /**
     * factory 빈과 resolver 메서드 메타데이터를 초기화한다.
     */
    private FactoryBridge initFactoryBridge() {
        Object factory = applicationContext.getBean(FACTORY_BEAN);
        Method resolveSync = findMethod(factory.getClass(), METHOD_RESOLVE_SYNC, 1);
        Method resolveStream = findMethod(factory.getClass(), METHOD_RESOLVE_STREAM, 1);
        return new FactoryBridge(factory, resolveSync, resolveStream);
    }

    /**
     * reflection invocation 예외를 runtime 예외로 정규화한다.
     */
    private RuntimeException unwrap(InvocationTargetException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Bridge invocation failed", cause);
    }

    /**
     * 런타임 enum 타입에서 상수 값을 안전하게 조회한다.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Enum<?> toEnumConstant(Class<?> enumClass, String constantName) {
        Class enumType = enumClass.asSubclass(Enum.class);
        return Enum.valueOf(enumType, constantName);
    }

    /**
     * 모델 문자열을 팩토리 enum 상수명으로 정규화한다.
     */
    private String mapModelType(String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return "OPENAI";
        }
        String normalized = rawModel.trim().toLowerCase(Locale.ROOT);
        String mapped = DIRECT_MODEL_MAPPING.get(normalized);
        if (mapped != null) {
            return mapped;
        }
        if (normalized.startsWith("gpt")) {
            return "OPENAI";
        }
        if (normalized.startsWith("gemini-2.5-flash-lite")) {
            return "GEMINI_LITE";
        }
        if (normalized.startsWith("gemini")) {
            return "GEMINI";
        }
        if (normalized.startsWith("mistral")) {
            return "MISTRAL";
        }
        throw new IllegalArgumentException("Unsupported model: " + rawModel);
    }

    /**
     * factory 호출에 필요한 resolver 메서드 바인딩.
     */
    private record FactoryBridge(Object factory, Method resolveSync, Method resolveStream) {
    }
}
