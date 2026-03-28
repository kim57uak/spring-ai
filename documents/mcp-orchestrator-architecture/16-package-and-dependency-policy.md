# 16. Package And Dependency Policy

## Base Package

- `com.example.springai`

## As-Is (Current Source)

- `controller.HttpChatController`
- `service.HttpChatService`
- `service.memory.SessionMemoryManager`
- `mcp.*` (`McpClientFactory`, `ProcessManager`, `StdioMcpClient`)

## To-Be (Agentic Layer)

- `controller.HttpChatController` (entry)
- `service.agent.orchestrator.AgentOrchestrator`
- `service.agent.graph.AgentStateGraphFactory`
- `service.agent.plan.PlanningService`
- `service.agent.execute.ToolExecutionService`
- `service.agent.compose.ResponseComposeService`
- `service.agent.store.ConversationStore`
- `service.agent.store.GraphCheckpointStore`
- `service.agent.security.AuthService`
- `service.agent.model.*`

## Design Rules

- `HttpChatController`를 단일 진입점으로 사용한다.
- 오케스트레이터는 인터페이스에만 의존한다.
- `LangGraph4j StateGraph` 조립 책임은 `graph` 계층으로 고정한다.
- planning/execute/compose/store/security 책임을 분리한다.
- tool 선택은 capability 기반 라우팅으로 구현해 도메인 고정명을 피한다.
- 저장소 구현(예: Redis)은 별도 계층으로 분리하되, 필요하면 기존 계층 직접 수정도 허용한다.

## Dependency Rules

- `controller -> orchestrator -> (plan/execute/compose/store/security/runtime)`
- `plan/compose -> spring-ai runtime port`
- `execute -> mcp client port`
- `store -> redis implementation`
- 상위 계층은 concrete class가 아니라 port(interface)에만 의존한다.

## Security Rules

- 입력 검증은 controller에서 수행한다.
- 인증/권한 검증 실패는 `HUMAN_MESSAGE`로 반환한다.
- tool 및 server는 allowlist로 제한한다.
- 로그에 token, raw prompt, 내부 세션 키를 남기지 않는다.

## LLM Cost Guardrail

- step당 1회 호출 + 실패 시 1회 재시도만 허용한다.
