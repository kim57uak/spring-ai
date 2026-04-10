# 24. Host Domain Class Model Pseudocode

```java
public record HostAgentRequest(
        String sessionId,
        String message,
        String model
) {}
```

```java
public record RoutingPlan(
        String agentKey,
        String method,
        String reason,
        Map<String, Object> arguments
) {}
```

```java
public record DownstreamCallResult(
        String agentKey,
        String status,
        String payload,
        String errorCode
) {}
```

```java
public interface A2AInvocationService {
    DownstreamCallResult invoke(RoutingPlan plan, HostPlanningContext context);
}
```

