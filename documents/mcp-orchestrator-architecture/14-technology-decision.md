# 14. Technology Decision

## Final Choice

- `Spring AI + LangGraph4j + Redis + A2A SDK(spec/core)`

## Decision Context (Current Source)

- 현재 소스의 진입점은 `HttpChatController`, 핵심 조율은 `HttpChatService`다.
- 세션 상태는 `ConversationStore`/`GraphCheckpointStore` 포트를 통해 Redis 구현체로 외부화되어 있다.
- 예외 처리는 `advice.GlobalExceptionHandler`와 `exception.*` 계층으로 분리되어 있다.
- MCP 호출은 `McpClientFactory`/`ProcessManager`/`StdioMcpClient`로 이미 분리되어 있다.
- `build.gradle`에 `spring-ai`와 `langgraph4j` 의존성이 이미 포함되어 있다.

## Why This Combination

- `Spring AI`: 모델/프롬프트/스트리밍 API를 통합해 LLM Provider 변경 비용을 낮춘다.
- `LangGraph4j`: planning, conditional edge, checkpoint resume을 코드 규칙으로 강제할 수 있다.
- `Redis`: 대화 히스토리, 그래프 상태, 체크포인트를 외부화해 수평 확장과 복구를 지원한다.
- 세 기술 모두 Spring Boot 기반에서 운영 표준화가 쉽다.

## Platform-Neutral Principles

- 특정 벤더 API 타입을 상위 계층에 노출하지 않는다.
- 모델 선택, MCP 서버 목록, 저장소 구현은 설정/어댑터로 교체 가능해야 한다.
- 오케스트레이터는 `tool group`과 `capability` 기준으로 라우팅하고, 도메인 고유명에 고정하지 않는다.

## Deferred Options

- `Spring AI only`: 단순 체인은 가능하지만 복잡한 handoff/재개 흐름의 관리 비용이 커진다.
- `LangChain 계열 추가`: 가능하지만 현재 범위에서는 불필요한 프레임워크 표면적 증가다.

## Scope

- As-Is: `http-chat + agent + mcp` 통합 구조 운영
- To-Be: agent 계층 고도화 + A2A 프로토콜 계층 통합
- 필수 구성요소: `Spring AI`, `LangGraph4j`, `Redis`, `A2A SDK(spec/core)`

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split 완료: `application.yml` imports `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

## 2026-04-10 A2A Core Integration (Doc 28)

- 기존 `/api/*-agent/*` 경로는 유지하고 `/a2a/*`를 병행 추가한다.
- 하위 에이전트에서 원격 포워딩/취소 전달은 도입하지 않는다.
- task lifecycle는 `A2ATaskStore`로 관리하고 scope ownership을 강제한다.
- 현재 버전 라인(`Spring AI 1.0.3`, `LangGraph4j 1.8.10`) 유지 전략을 채택한다.
