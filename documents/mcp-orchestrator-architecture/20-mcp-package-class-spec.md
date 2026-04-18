# 20. MCP Package / Class Specification

Last synchronized with source: 2026-04-18  
Source baseline: `src/main/java/com/example/springai`

## Base Package

- `com.example.springai`

## Current Package Structure

```text
src/main/java/com/example/springai
├── SpringAiApplication
├── advice
│   └── GlobalExceptionHandler
├── a2a
│   ├── A2aMethod
│   ├── context/A2aExecutionContext
│   ├── dto/{JsonRpcRequest, JsonRpcResponse, JsonRpcError, TaskSendParams, TaskIdParams, TaskQueryParams, TasksListParams, TasksListResult, TaskView}
│   ├── idempotency/A2aRequestIdempotencyService
│   ├── lifecycle/A2aLifecycleService
│   ├── mapper/A2AResponseMapper
│   ├── registry/AgentCardRegistry
│   └── task/{A2ATaskStore, A2aTaskSnapshot, A2aTaskStatus, InMemoryA2ATaskStore, RedisA2ATaskStore}
├── common/redis/{RedisKeyspace, RedisTtlPolicy}
├── config
│   ├── AgentCardProperties
│   ├── AgentScopeProperties
│   ├── HttpLlmProperties
│   ├── LlmRateLimitProperties
│   ├── LoggingConfig
│   ├── McpProperties
│   ├── ObservationConfig
│   ├── PromptProperties
│   └── SpringAiChatAdvisorConfig
├── controller
│   ├── a2a/{BaseA2AControllerSupport, ProductA2AController, ReservationA2AController, SearchA2AController, AgentCardController}
│   └── base/{BaseAgentControllerSupport, HttpChatController, ProductAgentController, ReservationAgentController, SearchAgentController}
├── dto/{ChatRequest, ChatResponse, ErrorResponse}
├── exception/{ChatProcessingException, McpException family}
├── mcp/{JsonRpcRequest, McpClient, McpClientFactory, StdioMcpClient, SseMcpClient, ProcessManager, McpProcessLauncher, ToolSchemaRegistry}
├── model/agent
│   ├── A2aStructuredResponse
│   ├── AgentChatRequest
│   ├── AgentGraphState
│   ├── AgentScope
│   ├── AgentScopeName
│   ├── ChunkType
│   ├── PlanningContext
│   ├── ToolExecutionResult
│   └── ToolPlan
├── service
│   ├── AgentScopeActivationService
│   ├── AgentScopeResolver
│   ├── HttpChatService
│   ├── ScopedAgentChatService
│   ├── chat/{ChatService, SyncChatService, StreamChatService, StructuredChatService, ChatModelType, ChatRequestContext, ModelChatServiceFactory, SpringAiCompatibleChatService, LlmCallPolicy, LlmRequestRateLimiter}
│   ├── chat/advisor/{PromptSanitizingAdvisor, SessionHistoryAdvisor, ChatAdvisorContextKeys}
│   ├── chat/model/{OpenAiModelChatService, GeminiModelChatService, GeminiLiteModelChatService, MistralModelChatService}
│   ├── chat/tool/McpToolCallbackProvider
│   ├── llm/LlmCredentialValidator
│   └── agent
│       ├── a2ui/{AgentStructuredDataExtractor, CompositeAgentStructuredDataExtractor, SaleProductStructuredDataExtractor, ScopedAgentStructuredDataExtractor}
│       ├── compose/{AgentTraceSummaryFormatter, LlmResponseComposeService, ResponseComposeService}
│       ├── execute/{McpToolExecutionService, ToolExecutionService}
│       ├── graph/{AgentStateGraphFactory, LangGraphAgentStateGraphFactory}
│       ├── orchestrator/AgentOrchestrator
│       ├── plan/{HeuristicPlanningService, PlanningService}
│       ├── prompt/{DefaultPromptTemplateService, PromptRenderService, PromptTemplateService}
│       ├── runtime/{AgentLlmRuntime, DefaultAgentLlmRuntime}
│       ├── security/{HumanMessageService, PromptInjectionGuard}
│       └── store/{ConversationStore, GraphCheckpointStore, redis/RedisConversationStore, redis/RedisGraphCheckpointStore}
```

## Current Core Contracts

- `PlanningService#plan(PlanningContext): List<ToolPlan>`
- `ToolExecutionService#execute(ToolPlan, PlanningContext): ToolExecutionResult`
- `ResponseComposeService#streamCompose(PlanningContext): Flux<String>`
- `AgentStateGraphFactory#getCompiledGraph(): CompiledGraph<AgentGraphState>`
- `AgentLlmRuntime#complete/completeStructured/stream(prompt, model, sessionId)`
- `ConversationStore#load/save/clear`
- `GraphCheckpointStore#loadCheckpoint/saveCheckpoint/clear`
- `A2ATaskStore#create/get/list/markRunning/markCompleted/markFailed/cancel`

## Source-backed Dependency Policy

- `controller.base -> HttpChatService -> AgentOrchestrator`
- `controller.base(scoped) -> ScopedAgentChatService -> AgentOrchestrator`
- `controller.a2a -> BaseA2AControllerSupport -> ScopedAgentChatService`
- `BaseA2AControllerSupport -> AgentScopeResolver + AgentScopeActivationService + A2aLifecycleService + A2aRequestIdempotencyService + A2AResponseMapper`
- `AgentOrchestrator -> ConversationStore + GraphCheckpointStore + AgentStateGraphFactory + ResponseComposeService + A2aLifecycleService`
- `LangGraphAgentStateGraphFactory -> PlanningService + ToolExecutionService`
- `HeuristicPlanningService -> ToolSchemaRegistry + AgentLlmRuntime + PromptRenderService + PromptInjectionGuard`
- `McpToolExecutionService -> McpClientFactory + ToolSchemaRegistry + McpProperties`
- `DefaultAgentLlmRuntime -> ModelChatServiceFactory`
- `ModelChatServiceFactory -> provider-specific chat services`

## Operational Constraints Reflected in Source

- `HttpChatController` remains unrestricted.
- scoped controllers and A2A controllers share the same scope model (`AgentScopeResolver`).
- A2A card exposure is configuration-aware via `AgentCardProperties`.
- Redis is optional:
  - conversation/checkpoint stores use Redis + local fallback
  - A2A task store switches between in-memory and Redis by property
- MCP schema lookup uses remote refresh + cache + static fallback, not static config only.

## Documentation Note

- This document intentionally reflects the current codebase, not the older “to-be minimal refactor” state.
- Planned-but-not-implemented router abstractions or `/a2a` global aggregator endpoints are excluded because they do not exist in `com.example.springai` source.
