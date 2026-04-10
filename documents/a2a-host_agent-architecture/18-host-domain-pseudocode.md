# 18. Host Domain Pseudocode

## Request

```java
public record HostAgentRequest(
        String sessionId,
        String message,
        String model
) {}
```

## Routing Plan

```java
public record RoutingPlan(
        String agentKey,
        String method,
        String reason,
        int priority,
        Map<String, Object> arguments
) {}
```

## Downstream Result

```java
public record DownstreamCallResult(
        String agentKey,
        String taskId,
        String status,
        String payload,
        String errorCode,
        String errorMessage
) {}
```

## Orchestrator Skeleton

```java
public Flux<String> execute(HostAgentRequest request) {
    HostPlanningContext context = loadContext(request);
    List<RoutingPlan> plans = planningService.plan(context);
    for (RoutingPlan plan : bounded(plans)) {
        DownstreamCallResult result = invocationService.invoke(plan, context);
        context.addResult(result);
    }
    return composeService.streamCompose(context)
            .doFinally(signal -> persist(context));
}
```

