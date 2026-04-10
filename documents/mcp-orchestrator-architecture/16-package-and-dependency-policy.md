# 16. Package And Dependency Policy

## Base Package

- `com.example.springai`

## As-Is (Current Source)

- `controller.base.HttpChatController`
- `controller.base.ProductAgentController`
- `controller.base.ReservationAgentController`
- `controller.base.SearchAgentController`
- `controller.a2a.*` (`BaseA2AControllerSupport`, `ProductA2AController`, `ReservationA2AController`, `SearchA2AController`, `AgentCardController`)
- `advice.GlobalExceptionHandler`
- `exception.*` (`ChatProcessingException`, `McpException` 계층)
- `service.HttpChatService`
- `service.ScopedAgentChatService`
- `service.AgentScopeResolver`
- `service.AgentScopeActivationService`
- `service.chat.*` (`ChatService`, `SyncChatService`, `StreamChatService`, `StructuredChatService`, `ModelChatServiceFactory`, `ChatModelType`, `ChatRequestContext`)
- `service.chat.model.*` (`OpenAiModelChatService`, `GeminiModelChatService`, `GeminiLiteModelChatService`, `MistralModelChatService`)
- `service.chat.advisor.*` (`PromptSanitizingAdvisor`, `SessionHistoryAdvisor`)
- `service.chat.tool.McpToolCallbackProvider`
- `service.llm.LlmCredentialValidator`
- `mcp.*` (`McpClientFactory`, `ProcessManager`, `StdioMcpClient`, `SseMcpClient`, `ToolSchemaRegistry`)
- `config.*` (`HttpLlmProperties`, `LlmRateLimitProperties`, `McpProperties`, `ObservationConfig`, `SpringAiChatAdvisorConfig`)

## To-Be (Agentic Layer)

- `controller.base.HttpChatController` (unrestricted entry)
- `controller.base.ProductAgentController` (scoped entry)
- `controller.base.ReservationAgentController` (scoped entry)
- `controller.base.SearchAgentController` (scoped entry)
- `controller.a2a.*` (`ProductA2AController`, `ReservationA2AController`, `SearchA2AController`, `AgentCardController`)
- `service.agent.orchestrator.AgentOrchestrator`
- `service.agent.graph.AgentStateGraphFactory`
- `service.agent.plan.PlanningService`
- `service.agent.execute.ToolExecutionService`
- `service.agent.compose.ResponseComposeService`
- `service.agent.store.ConversationStore`
- `service.agent.store.GraphCheckpointStore`
- `service.agent.security.HumanMessageService`
- `service.agent.security.PromptInjectionGuard`
- `model.agent.*` (`AgentChatRequest`, `PlanningContext`, `ToolPlan`, `ToolExecutionResult`, `ChunkType`, `AgentGraphState`)
- `a2a.dto.*` (JSON-RPC contracts)
- `a2a.task.*` (`A2ATaskStore`, task lifecycle/ownership)
- `a2a.registry.*` (`AgentCardRegistry`)
- `a2a.mapper.*` (A2A response mapping)

## Design Rules

- `HttpChatController`는 하위호환을 위해 unrestricted MCP 접근을 유지한다.
- 신규 Product/Reservation/Search 컨트롤러는 scoped MCP 접근을 사용한다.
- 예외 응답은 `GlobalExceptionHandler`에서 일원화한다.
- 오케스트레이터는 인터페이스에만 의존한다.
- `LangGraph4j StateGraph` 조립 책임은 `graph` 계층으로 고정한다.
- planning/execute/compose/store/security/prompt/runtime 책임을 분리한다.
- tool 선택은 capability 기반 라우팅으로 구현해 도메인 고정명을 피한다.
- 저장소 구현(예: Redis)은 별도 계층으로 분리하되, 필요하면 기존 계층 직접 수정도 허용한다.

## Dependency Rules

- `controller -> service(HttpChatService) -> orchestrator -> (plan/execute/compose/store/security/prompt/runtime)`
- `controller -> service(ScopedAgentChatService) -> scopeResolver -> orchestrator -> (plan/execute/compose/store/security/prompt/runtime)`
- `controller.a2a -> service(ScopedAgentChatService) -> scopeResolver -> orchestrator -> (plan/execute/compose/store/security/prompt/runtime) + a2a.task`
- `controller/advice -> exception`
- `plan/compose -> runtime(AgentLlmRuntime) -> chat factory -> provider chat service`
- `execute -> mcp client port`
- `store -> redis implementation`
- 상위 계층은 concrete class가 아니라 port(interface)에만 의존한다.

## A2A Dependency Rules (Doc 28)

- A2A 컨트롤러는 반드시 scoped service 경로만 사용한다.
- `tasks/get|cancel|list`는 `A2ATaskStore`를 통해 동일 scope task만 허용한다.
- 하위 에이전트 내부에서 다른 에이전트/컨트롤러로 원격 포워딩하지 않는다.
- A2A 미사용 경로(`api/*`)는 기존 동작을 그대로 유지한다.

## Security Rules

- 입력 검증은 controller에서 수행한다.
- 사용자/툴 결과 텍스트는 `PromptInjectionGuard`를 통해 정제한다.
- 인증/권한 검증 실패는 `HUMAN_MESSAGE`로 반환한다.
- tool 및 server는 allowlist로 제한한다.
- 로그에 token, raw prompt, 내부 세션 키를 남기지 않는다.

## LLM Cost Guardrail

- 기본 정책은 `LlmCallPolicy` + `LlmRequestRateLimiter`로 통합한다.
- 재시도/백오프는 `llm.rate-limit.*` 설정으로 운영 환경에서 조정한다.

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split 완료: `application.yml` imports `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

## 2026-04-10 A2A Package Alignment (Doc 28)

- 추가 패키지: `controller.a2a`, `a2a.dto`, `a2a.task`, `a2a.registry`, `a2a.mapper`
- 기존 패키지 계약은 유지하고, A2A는 additive 방식으로 확장한다.
