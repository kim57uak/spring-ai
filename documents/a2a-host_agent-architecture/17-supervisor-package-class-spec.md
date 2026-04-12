# 17. Supervisor Package/Class Specification

## Recommended Package Structure

```text
src/main/java/com/example/springsupervisorai
├── config
│   ├── A2aSupervisorRoutingProperties
│   ├── SupervisorPromptProperties
│   └── SupervisorStreamProperties
├── controller
│   └── SupervisorA2AController
├── service
│   ├── SupervisorAgentService
│   └── SupervisorAgentOrchestrator
├── service/agent
│   ├── plan/{SupervisorPlanningService, LlmSupervisorPlanningService}
│   ├── invoke/{A2AInvocationService, DefaultA2AInvocationService, A2AClientRegistry}
│   ├── compose/{SupervisorResponseComposeService, LlmSupervisorResponseComposeService}
│   ├── graph/{SupervisorStateGraphFactory, LangGraphSupervisorStateGraphFactory}
│   └── store/{ConversationStore, GraphCheckpointStore}
├── a2a
│   ├── A2AJsonRpcClient
│   ├── A2ARequestMapper
│   └── A2AResponseMapper
└── model
    ├── SupervisorAgentRequest
    ├── SupervisorPlanningContext
    ├── RoutingPlan
    └── DownstreamCallResult
```

## Core Contracts

- `SupervisorPlanningService#plan(context): List<RoutingPlan>`
- `A2AInvocationService#invoke(plan, context): DownstreamCallResult`
- `SupervisorResponseComposeService#streamCompose(context): Flux<String>`
- `SupervisorStateGraphFactory#getCompiledGraph(): CompiledGraph<SupervisorGraphState>`
