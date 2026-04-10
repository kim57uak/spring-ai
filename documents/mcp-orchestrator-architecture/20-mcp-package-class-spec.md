# 20. MCP Package/Class Specification

## Base Package

- `com.example.springai`

## Scope

- As-Is 참고: `controller.base.HttpChatController`, `service.HttpChatService`, `mcp.*`
- To-Be/Current: `service.agent.*` 중심 Agentic orchestration + `advice`/`exception` 분리
- 필수 런타임: `Spring AI`, `LangGraph4j`, `Redis`

## Recommended Package Structure

```text
src/main/java/com/example/springai
├── config
│   ├── HttpLlmProperties
│   ├── LlmRateLimitProperties
│   ├── McpProperties
│   ├── ObservationConfig
│   └── SpringAiChatAdvisorConfig
├── advice
│   └── GlobalExceptionHandler
├── controller
│   ├── base/{BaseAgentControllerSupport, HttpChatController, ProductAgentController, ReservationAgentController, SearchAgentController}
│   └── a2a/{BaseA2AControllerSupport, ProductA2AController, ReservationA2AController, SearchA2AController, AgentCardController}
├── exception
│   ├── ChatProcessingException
│   └── McpException + subclasses
├── service
│   ├── HttpChatService
│   ├── ScopedAgentChatService
│   ├── AgentScopeResolver
│   ├── chat/{ChatService, SyncChatService, StreamChatService, StructuredChatService, ChatModelType, ChatRequestContext, ModelChatServiceFactory, SpringAiCompatibleChatService, LlmCallPolicy, LlmRequestRateLimiter}
│   ├── chat/model/{OpenAiModelChatService, GeminiModelChatService, GeminiLiteModelChatService, MistralModelChatService}
│   ├── chat/advisor/{PromptSanitizingAdvisor, SessionHistoryAdvisor}
│   ├── chat/tool/{McpToolCallbackProvider}
│   ├── llm/{LlmCredentialValidator}
│   └── agent
│       ├── orchestrator/AgentOrchestrator
│       ├── graph/AgentStateGraphFactory
│       ├── plan/PlanningService
│       ├── execute/ToolExecutionService
│       ├── compose/ResponseComposeService
│       ├── prompt/PromptTemplateService
│       ├── runtime/AgentLlmRuntime
│       ├── store/{ConversationStore, GraphCheckpointStore}
│       └── security/{HumanMessageService, PromptInjectionGuard}
├── mcp
│   ├── McpClientFactory
│   ├── ProcessManager
│   ├── StdioMcpClient
│   ├── SseMcpClient
│   └── ToolSchemaRegistry
├── a2a
│   ├── dto/{JsonRpcRequest, JsonRpcResponse, A2A task/message contracts}
│   ├── task/{A2ATaskStore, InMemoryA2ATaskStore}
│   ├── context/{A2aExecutionContext}
│   ├── lifecycle/{A2aLifecycleService}
│   ├── registry/{AgentCardRegistry}
│   └── mapper/{A2AResponseMapper}
└── model/agent
    ├── AgentScope
    ├── AgentChatRequest
    ├── AgentGraphState
    ├── PlanningContext
    ├── ToolPlan
    ├── ToolExecutionResult
    └── ChunkType
```

## Core Contracts

- `PlanningService#plan(PlanningContext): List<ToolPlan>`
- `ToolExecutionService#execute(ToolPlan, PlanningContext)`
- `ResponseComposeService#streamCompose(PlanningContext)`
- `ModelChatServiceFactory#resolveSync/resolveStream/resolveStructured(ChatModelType)`
- `AgentLlmRuntime#complete/completeStructured/stream(prompt, model, sessionId)`
- `SpringAiCompatibleChatService#generate/streamGenerate/generateStructured(message, context)`
- `ConversationStore#load/save(sessionId, history)`
- `GraphCheckpointStore#loadCheckpoint/saveCheckpoint(sessionId, checkpoint)`
- `A2ATaskStore#create/get/list/cancel/markRunning/markCompleted/markFailed`

## Dependency Policy

- `controller -> orchestrator -> ports(plan/execute/compose/store/runtime/security)`
- `controller -> service(HttpChatService) -> orchestrator -> ports(plan/execute/compose/store/runtime/security/prompt)`
- `advice -> exception`
- `runtime(DefaultAgentLlmRuntime) -> chat(ModelChatServiceFactory -> provider ChatService)`
- 구현체는 `impl` 또는 기능별 패키지에 둔다.
- 상위 계층은 concrete class 의존 금지, interface 의존만 허용한다.
- provider/tool/storage 변경 시 필요한 계층은 직접 수정/리팩토링할 수 있다.
- A2A는 기존 API와 병행 운영하며 core orchestrator를 재사용한다.

## Security and Reliability

- tool/server allowlist 필수
- `HttpChatController`: unrestricted MCP 접근
- Product/Reservation/Search: scoped MCP 접근 (`allowedServers`, `allowedToolsByServer`)
- 인증/권한/입력 오류는 `HUMAN_MESSAGE`로 반환
- LLM 호출 정책은 `LlmCallPolicy` + `llm.rate-limit.*` 설정으로 운영 조정
- raw prompt/token/internal key/full MCP payload 로그 금지
- A2A task 조회/취소는 scope ownership 검증 필수

## Migration Rules

- 기존 `HttpChatController` API 계약은 필요 시 변경 가능하다.
- 신규 Agent 경로 병행 운영 또는 기존 경로 대체 전환 모두 허용한다.
- 공통 코드 추출은 직접 리팩토링을 기본으로 하고, 패턴 적용은 선택한다.
- A2A 도입 시 기본 전략은 병행 운영(기존 `/api/*` 유지)이다.
- 대체 전환은 회귀 게이트 통과 후 별도 결정한다.

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split 완료: `application.yml` imports `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

## 2026-04-10 A2A Class Spec Alignment (Doc 28)

- A2A controller/DTO/task/lifecycle 패키지를 additive 방식으로 추가
- `ScopedAgentChatService`/`AgentOrchestrator`에 A2A execution context를 전달해 lifecycle 동기화
