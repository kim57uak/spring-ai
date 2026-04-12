# 20. Supervisor Routing Package/Class Spec

## Scope

- 진입점은 `SupervisorA2AController` 단일 경로(`/a2a/supervisor`)만 사용한다.
- 하위 에이전트 연동은 `A2AInvocationService`를 통해서만 수행한다.

## Core Classes

- `SupervisorA2AController`
- `SupervisorAgentService`
- `SupervisorAgentOrchestrator`
- `SupervisorPlanningService`
- `A2AInvocationService`
- `SupervisorResponseComposeService`
- `A2AClientRegistry`
- `A2AJsonRpcClient`

## Core Contracts

- `SupervisorPlanningService#plan(context): List<RoutingPlan>`
- `A2AInvocationService#invoke(plan, context): DownstreamCallResult`
- `SupervisorResponseComposeService#streamCompose(context): Flux<String>`
