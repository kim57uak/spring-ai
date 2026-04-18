# 18. SpringAI To Usecase Mapping

Last synchronized with source: 2026-04-18  
Source baseline: `src/main/java/com/example/springai`

## Core Runtime Mapping

- `HttpChatService`
  - unrestricted HTTP 진입점이다. `AgentScopeResolver#resolveUnrestricted()`를 사용해 전체 MCP 접근을 허용한다.
- `ScopedAgentChatService`
  - `product`, `reservation`, `search` 스코프용 공통 진입점이다.
  - 일반 scoped HTTP 요청과 A2A 요청 모두 이 서비스를 통해 `AgentOrchestrator`로 들어간다.
- `AgentOrchestrator`
  - 현재 소스의 실행 중심점이다.
  - history/checkpoint load -> LangGraph invoke -> compose -> persistence -> A2A lifecycle 동기화를 담당한다.
- `LangGraphAgentStateGraphFactory`
  - 실제 그래프는 `plan -> execute(optional, loop up to 4) -> compose` 구조다.
- `HeuristicPlanningService`
  - LLM + tool schema catalog 기반 planner다.
  - `ToolSchemaRegistry`에서 scope-filtered tool 목록을 조회한 뒤 JSON 계획을 `ToolPlan`으로 정규화한다.
- `McpToolExecutionService`
  - planner가 선택한 MCP tool을 실행한다.
  - 도구 정책(`operation`, `retryable`, `maxCallsPerRequest`, `requireIdempotencyKey`)은 `McpProperties.ServerConfig.toolPolicies`를 사용한다.
- `LlmResponseComposeService`
  - tool execution 결과를 최종 사용자 응답 스트림으로 합성한다.
- `DefaultAgentLlmRuntime`
  - planner/compose 계층이 공통으로 사용하는 LLM 포트다.
  - provider 문자열을 `ChatModelType`으로 변환하고 `ModelChatServiceFactory`에 위임한다.

## Provider / Chat Mapping

- `ModelChatServiceFactory`
  - `SyncChatService`, `StreamChatService`, `StructuredChatService` 구현체를 모델 종류별로 선택한다.
- `SpringAiCompatibleChatService`
  - OpenAI 호환 HTTP 호출 공통층이다.
  - `LlmCallPolicy`, `LlmRequestRateLimiter`, `McpToolCallbackProvider`를 공통 적용한다.
- provider 구현체
  - `OpenAiModelChatService`
  - `GeminiModelChatService`
  - `GeminiLiteModelChatService`
  - `MistralModelChatService`

## MCP Mapping

- `ToolSchemaRegistry`
  - startup prewarm + scope cache + server cache + remote reconnect-first + static `allowTools` fallback 구조다.
- `McpClientFactory`
  - 서버 설정의 `transport`에 따라 `SseMcpClient` 또는 `StdioMcpClient`를 생성한다.
- `McpProperties`
  - 현재 문서 기준 핵심 필드:
  - `transport`, `host`, `endpoint`, `command`, `args`, `env`, `allowTools`, `toolPolicies`, `timeoutMs`

## State / Persistence Mapping

- `ConversationStore`
  - 구현체: `RedisConversationStore`
  - Redis 실패 시 로컬 메모리 폴백을 유지한다.
- `GraphCheckpointStore`
  - 구현체: `RedisGraphCheckpointStore`
  - Redis 실패 시 로컬 메모리 폴백을 유지한다.
- `PlanningContext`
  - planner/executor/compose가 공유하는 실행 상태 객체다.
- `AgentGraphState`
  - LangGraph 내부 상태 저장 형식이며, `PlanningContext`로 변환된다.

## A2A Mapping

- A2A controller layer
  - `ProductA2AController`
  - `ReservationA2AController`
  - `SearchA2AController`
  - 모두 `BaseA2AControllerSupport`를 공통 베이스로 사용한다.
- A2A protocol compatibility
  - unary: `/a2a/{scope}`
  - stream: `/a2a/{scope}` with stream method
  - alias: `/a2a/{scope}/stream`
  - supported method families:
    - `SendMessage` / `message/send`
    - `SendStreamingMessage` / `message/stream`
    - `GetTask` / `tasks/get`
    - `CancelTask` / `tasks/cancel`
    - `ListTasks` / `tasks/list`
- `A2aLifecycleService`
  - task create / get / list / cancel / completed / failed 상태 전이를 담당한다.
  - `scope + sessionId` ownership 검증 오버로드를 제공한다.
- `A2ATaskStore`
  - 구현체:
    - `InMemoryA2ATaskStore` when `app.redis.enabled=false`
    - `RedisA2ATaskStore` when `app.redis.enabled=true`
- `A2aRequestIdempotencyService`
  - A2A unary 요청의 in-flight dedupe + completed response cache를 담당한다.
- `A2AResponseMapper`
  - 내부 `A2aTaskSnapshot`을 A2A `TaskView`로 변환한다.
- `AgentCardRegistry` + `AgentCardController`
  - `/.well-known/agent.json`
  - `/a2a/{scope}/.well-known/agent.json`
  - `agent.cards.enabled-scopes`와 `agent.scopes.*`를 합성해 노출 범위를 결정한다.

## Current Usecase Grouping

- `product`
  - 상품 조회/등록 중심 요청
- `reservation`
  - 예약 조회/생성/변경/취소 중심 요청
- `search`
  - 검색/요약/리서치 중심 요청

## Source-backed Constraints

- `HttpChatController`는 계속 unrestricted path로 유지된다.
- scoped controller와 A2A controller는 모두 `AgentScopeResolver`를 통해 동일 scope 정책을 사용한다.
- controller 간 포워딩은 없다.
- A2A task ownership은 `scopeName`과 `sessionId` 둘 다 기준으로 보호된다.
