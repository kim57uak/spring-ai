# 17. Host Package/Class Specification

## Recommended Package Structure

```text
src/main/java/com/example/springai/host
├── config
│   ├── HostAgentProperties
│   └── A2aHostRoutingProperties
├── controller
│   └── HostA2AController
├── service
│   ├── HostAgentService
│   └── HostAgentOrchestrator
├── service/agent
│   ├── plan/{HostPlanningService, LlmHostPlanningService}
│   ├── invoke/{A2AInvocationService, DefaultA2AInvocationService, A2AClientRegistry}
│   ├── compose/{HostResponseComposeService, LlmHostResponseComposeService}
│   ├── graph/{HostStateGraphFactory, LangGraphHostStateGraphFactory}
│   └── store/{ConversationStore, GraphCheckpointStore}
├── a2a
│   ├── A2AJsonRpcClient
│   ├── A2ARequestMapper
│   └── A2AResponseMapper
└── model
    ├── HostAgentRequest
    ├── HostPlanningContext
    ├── RoutingPlan
    └── DownstreamCallResult
```

## Core Contracts

- `HostPlanningService#plan(context): List<RoutingPlan>`
- `A2AInvocationService#invoke(plan, context): DownstreamCallResult`
- `HostResponseComposeService#streamCompose(context): Flux<String>`
- `HostStateGraphFactory#getCompiledGraph(): CompiledGraph<HostGraphState>`
