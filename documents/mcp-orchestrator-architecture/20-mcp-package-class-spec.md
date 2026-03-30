# 20. MCP Package/Class Specification

## Base Package

- `com.example.springai`

## Scope

- As-Is 참고: `controller.HttpChatController`, `service.HttpChatService`, `mcp.*`
- To-Be/Current: `service.agent.*` 중심 Agentic orchestration + `advice`/`exception` 분리
- 필수 런타임: `Spring AI`, `LangGraph4j`, `Redis`

## Recommended Package Structure

```text
src/main/java/com/example/springai
├── advice
│   └── GlobalExceptionHandler
├── controller
│   ├── HttpChatController
│   └── (single entry)
├── exception
│   ├── ChatProcessingException
│   └── McpException + subclasses
├── service
│   ├── HttpChatService
│   ├── chat/{ChatService, SyncChatService, StreamChatService, LlmCallPolicy}
│   ├── llm/{LlmApiClient, ResponseParser}
│   ├── parser/{AbstractResponseParser, OpenAiResponseParser, GeminiResponseParser, MistralResponseParser}
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
│   └── StdioMcpClient
└── model/agent
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
- `ConversationStore#load/save(sessionId, history)`
- `GraphCheckpointStore#loadCheckpoint/saveCheckpoint(sessionId, checkpoint)`
- `AgentLlmRuntime#complete/stream(prompt, options)`

## Dependency Policy

- `controller -> orchestrator -> ports(plan/execute/compose/store/runtime/security)`
- `controller -> service(HttpChatService) -> orchestrator -> ports(plan/execute/compose/store/runtime/security/prompt)`
- `advice -> exception`
- 구현체는 `impl` 또는 기능별 패키지에 둔다.
- 상위 계층은 concrete class 의존 금지, interface 의존만 허용한다.
- provider/tool/storage 변경 시 필요한 계층은 직접 수정/리팩토링할 수 있다.

## Security and Reliability

- tool/server allowlist 필수
- 인증/권한/입력 오류는 `HUMAN_MESSAGE`로 반환
- LLM 호출 상한: step당 1회 + 재시도 1회
- raw prompt/token/internal key/full MCP payload 로그 금지

## Migration Rules

- 기존 `HttpChatController` API 계약은 필요 시 변경 가능하다.
- 신규 Agent 경로 병행 운영 또는 기존 경로 대체 전환 모두 허용한다.
- 공통 코드 추출은 직접 리팩토링을 기본으로 하고, 패턴 적용은 선택한다.
