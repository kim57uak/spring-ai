# 20. Host Routing Package/Class Spec

## Scope

- 진입점은 `HostA2AController` 단일 경로(`/a2a/host/*`)만 사용한다.
- 하위 에이전트 연동은 `A2AInvocationService`를 통해서만 수행한다.

## Core Classes

- `HostA2AController`
- `HostAgentService`
- `HostAgentOrchestrator`
- `HostPlanningService`
- `A2AInvocationService`
- `HostResponseComposeService`
- `A2AClientRegistry`
- `A2AJsonRpcClient`

## Core Contracts

- `HostPlanningService#plan(context): List<RoutingPlan>`
- `A2AInvocationService#invoke(plan, context): DownstreamCallResult`
- `HostResponseComposeService#streamCompose(context): Flux<String>`

