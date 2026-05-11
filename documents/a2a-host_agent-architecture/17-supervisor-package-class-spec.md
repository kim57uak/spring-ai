# 17. Supervisor Package / Class Specification

Last synchronized with source: 2026-05-11  
Source baseline: `src/main/java/com/example/springsupervisorai`

## Current Package Structure

```text
src/main/java/com/example/springsupervisorai
├── a2a
│   ├── A2AJsonRpcClient
│   ├── A2ARequestMapper
│   ├── A2AResponseMapper
│   ├── dto/{JsonRpcRequest, JsonRpcResponse, JsonRpcError, TaskIdParams, TaskQueryParams, TaskSendParams, TaskReviewGetParams, TaskReviewDecisionParams, TaskView, TaskReviewView, TasksListParams, TasksListResult}
│   ├── idempotency/SupervisorRequestIdempotencyService
│   ├── lifecycle/SupervisorA2aLifecycleService
│   └── task/{A2ATaskStore, A2aTaskSnapshot, A2aTaskSnapshotTransitions, A2aTaskStatus, InMemoryA2ATaskStore, RedisA2ATaskStore}
├── common/redis/{RedisKeyspace, RedisTtlPolicy}
├── config/{A2aSupervisorRoutingProperties, SupervisorPromptProperties, SupervisorStreamProperties}
├── controller/{SupervisorA2AController, SupervisorA2ARequestValidator}
├── exception/{A2ARoutingException, DownstreamA2AException, SupervisorChatProcessingException}
├── model
│   ├── DownstreamCallResult
│   ├── HandoffDirective
│   ├── HandoffPolicyContext
│   ├── HandoffValidationResult
│   ├── HitlDecisionType
│   ├── HitlPolicyContext
│   ├── HitlPolicyResult
│   ├── HitlReviewStatus
│   ├── HitlReviewTicket
│   ├── InvocationPolicyContext
│   ├── RoutingPlan
│   ├── SupervisorA2aMethod
│   ├── SupervisorAgentRequest
│   ├── SupervisorErrorCode
│   ├── SupervisorExecutionRequest
│   ├── SupervisorGraphNode
│   ├── SupervisorGraphRoute
│   ├── SupervisorGraphSnapshot
│   ├── SupervisorGraphState
│   ├── SupervisorInvocationStatus
│   ├── SupervisorOutputEvent
│   ├── SupervisorOutputEventType
│   ├── SupervisorPlanningContext
│   ├── SupervisorProgressEvent
│   ├── SupervisorRuntimeState
│   └── SwarmState
├── service
│   ├── HitlGateService
│   ├── SupervisorAgentOrchestrator
│   ├── SupervisorAgentService
│   ├── SupervisorExceptionTranslator
│   ├── SupervisorExecutionPersistenceService
│   ├── SupervisorExecutionResultCollector
│   ├── SupervisorExecutionService
│   ├── SupervisorExecutionStateLoader
│   ├── SupervisorExecutionSummaryEmitter
│   ├── SupervisorFallbackInvokeService
│   ├── SupervisorGraphExecutionService
│   ├── SupervisorHandoffProgressSupport
│   ├── SupervisorOutputEventSupport
│   ├── SupervisorPreHitlA2uiService
│   ├── SupervisorProgressPublisher
│   ├── SupervisorProgressSupport
│   ├── SupervisorReviewApplicationService
│   ├── SupervisorStreamProgressService
│   ├── SupervisorTaskFacade
│   ├── agent/a2ui/common/{A2uiComposePromptProvider, A2uiComposePromptProviderRegistry, A2uiTemplateView, CompositeSupervisorA2uiService, SupervisorA2uiDomainService, SupervisorA2uiService, SupervisorA2uiSupport}
│   ├── agent/a2ui/product/{AbstractProductA2uiTemplate, BookingProductA2uiTemplate, CreationFormProductA2uiTemplate, DefaultSupervisorProductInfoA2uiService, PricingProductA2uiTemplate, ProductA2uiComposePromptProvider, ProductA2uiDataMapper, ProductA2uiMessageBuilder, ProductA2uiTemplate, ProductA2uiTemplateRegistry, ProductPayloadExtractor, ProductPresentationModel, SummaryProductA2uiTemplate, TimelineProductA2uiTemplate}
│   ├── agent/a2ui/reservation/{DefaultSupervisorReservationA2uiService, ReservationA2uiComposePromptProvider, ReservationA2uiMessageBuilder, ReservationPayloadExtractor, ReservationPresentationModel}
│   ├── agent/compose/{A2uiDecisionParser, ComposeOutcomeAnalyzer, ComposePromptBuilder, LlmSupervisorResponseComposeService, SupervisorResponseComposeService}
│   ├── agent/graph/{LangGraphSupervisorStateGraphFactory, SupervisorBatchExecutionPolicy, SupervisorGraphInputBuilder, SupervisorGraphStateMapper, SupervisorPlanRunner, SupervisorStateGraphFactory}
│   ├── agent/handoff/{DefaultHandoffPolicyService, HandoffPolicyService}
│   ├── agent/hitl/{DefaultHitlDecisionService, HitlDecisionService, HitlPolicyService, LlmHitlPolicyService}
│   ├── agent/invoke/{A2AClientRegistry, A2AInvocationService, DefaultA2AInvocationService, DownstreamAgentCardCache}
│   ├── agent/plan/{LlmSupervisorPlanningService, SupervisorPlanningService}
│   ├── agent/result/DownstreamResultInterpreter
│   ├── agent/runtime/{DefaultSupervisorLlmRuntime, ReflectionSupervisorChatGateway, SupervisorChatGateway, SupervisorLlmRuntime}
│   ├── agent/security/PromptInjectionGuard
│   ├── agent/store/{ConversationStore, GraphCheckpointStore, InMemorySupervisorReviewStore, InMemorySupervisorSwarmStateStore, RedisSupervisorReviewStore, RedisSupervisorSwarmStateStore, SupervisorReviewStore, SupervisorSwarmStateStore, redis/RedisConversationStore, redis/RedisGraphCheckpointStore}
│   ├── agent/swarm/{DefaultSupervisorSwarmCoordinator, SupervisorSwarmCoordinator, SwarmStateVersionConflictException}
│   └── prompt/SupervisorPromptRenderService
```

## Current Core Contracts

- `SupervisorPlanningService#plan(context): List<RoutingPlan>`
- `HitlPolicyService#evaluate(context): HitlPolicyResult`
- `HitlDecisionService#openReview/getReview/decide(...)`
- `A2AInvocationService#invoke(context): DownstreamCallResult`
- `HandoffPolicyService#evaluate(context): List<HandoffValidationResult>`
- `SupervisorResponseComposeService#streamComposeEvents(context): Flux<SupervisorOutputEvent>`
- `SupervisorStateGraphFactory#getCompiledGraph(): CompiledGraph<SupervisorGraphState>`
- `SupervisorSwarmCoordinator#applyRoutingRule/loadLatestBySession/...`
- `SupervisorA2uiService#build(context, selectedView, message): Optional<A2uiRenderResult>`
- `SupervisorExecutionService#executeSync/executeStreamEvents/resumeApprovedTask/resumeApprovedTaskStream`
- `SupervisorReviewApplicationService#decideReview/decideReviewStream`
- `SupervisorTaskFacade#createRunningTask/createWaitingReviewTask/getTask/listTasks/cancelTask`

## Source-backed Dependency Shape

- `SupervisorA2AController -> SupervisorA2ARequestValidator -> SupervisorAgentService`
- `SupervisorAgentService -> SupervisorPreHitlA2uiService + HitlGateService + SupervisorExecutionService + SupervisorReviewApplicationService + SupervisorTaskFacade`
- `SupervisorExecutionService -> SupervisorAgentOrchestrator + SupervisorTaskFacade + SupervisorExecutionResultCollector`
- `SupervisorAgentOrchestrator -> SupervisorGraphExecutionService + SupervisorResponseComposeService + SupervisorExecutionPersistenceService + SupervisorFallbackInvokeService + SupervisorProgressPublisher + SupervisorA2aLifecycleService`
- `SupervisorGraphExecutionService -> SupervisorExecutionStateLoader + SupervisorStateGraphFactory`
- `LangGraphSupervisorStateGraphFactory -> SupervisorPlanningService + HandoffPolicyService + SupervisorSwarmCoordinator + SupervisorBatchExecutionPolicy + SupervisorPlanRunner`
- `SupervisorPlanRunner -> A2AInvocationService`
- `DefaultA2AInvocationService -> A2AClientRegistry + A2ARequestMapper + A2AJsonRpcClient`
- `LlmSupervisorPlanningService / LlmHitlPolicyService / LlmSupervisorResponseComposeService -> DefaultSupervisorLlmRuntime`

## Current Source Constraints

- supervisor entrypoint is `/a2a/supervisor`; no global multi-controller forwarding layer exists.
- pre-HITL A2UI can terminate unary/stream requests before HITL/execution.
- review decision supports unary and streaming variants.
- A2UI support is split into:
  - common registry/router
  - product domain builder
  - reservation domain builder
- task persistence, review persistence, swarm persistence are independent stores.

## Documentation Note

- This file reflects the current `springsupervisorai` package tree and public contracts.
- Planning/proposal documents in the same folder remain historical design artifacts unless they explicitly state current synchronization.
