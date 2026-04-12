package com.example.springsupervisorai.service.agent.invoke;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.exception.A2ARoutingException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * supervisor -> downstream A2A 라우팅 정책 레지스트리.
 * <p>
 * 기능:
 * - agentKey 기반 endpoint/method/timeout 조회
 * - method allowlist 검증
 * - retry 정책 노출
 */
@Component
public class A2AClientRegistry {

    private final A2aSupervisorRoutingProperties routingProperties;

    /**
     * 라우팅 설정 의존성을 주입받는다.
     *
     * @param routingProperties supervisor A2A 라우팅/재시도 설정
     */
    public A2AClientRegistry(A2aSupervisorRoutingProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    /**
     * agentKey와 요청 메서드로 실제 호출 타겟을 해석한다.
     *
     * @param agentKey downstream agent 식별자
     * @param requestedMethod 요청 메서드(비어 있으면 route 기본 메서드 사용)
     * @return 검증 완료된 라우트 타겟
     */
    public A2ARouteTarget resolve(String agentKey, String requestedMethod) {
        A2aSupervisorRoutingProperties.Route route = routingProperties.getRouting().get(agentKey);
        if (route == null || route.getEndpoint() == null || route.getEndpoint().isBlank()) {
            throw new A2ARoutingException("Unknown or blocked downstream agent: " + agentKey);
        }
        String method = requestedMethod == null || requestedMethod.isBlank() ? route.getMethod() : requestedMethod;
        if (!routingProperties.getAllowedMethods().contains(method)) {
            throw new A2ARoutingException("A2A method is not allowed: " + method);
        }
        int timeoutMs = Math.max(100, route.getTimeoutMs());
        return new A2ARouteTarget(agentKey, route.getEndpoint(), method, Duration.ofMillis(timeoutMs));
    }

    /**
     * supervisor 전역 retry 정책을 반환한다.
     *
     * @return retry 정책 객체
     */
    public A2aSupervisorRoutingProperties.Retry retryPolicy() {
        return routingProperties.getRetry();
    }

    /**
     * supervisor 전역 circuit breaker 정책을 반환한다.
     *
     * @return circuit breaker 정책 객체
     */
    public A2aSupervisorRoutingProperties.CircuitBreaker circuitBreakerPolicy() {
        return routingProperties.getCircuitBreaker();
    }

    /**
     * A2A 호출에 필요한 불변 라우트 타겟 값 객체.
     *
     * @param agentKey downstream agent key
     * @param endpoint downstream endpoint URL
     * @param method JSON-RPC method
     * @param timeout 호출 타임아웃
     */
    public record A2ARouteTarget(String agentKey, String endpoint, String method, Duration timeout) {
        /**
         * 필수 필드 null 여부를 검증한다.
         */
        public A2ARouteTarget {
            Objects.requireNonNull(agentKey);
            Objects.requireNonNull(endpoint);
            Objects.requireNonNull(method);
            Objects.requireNonNull(timeout);
        }
    }
}
