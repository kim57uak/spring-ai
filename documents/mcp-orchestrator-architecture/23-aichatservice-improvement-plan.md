# 23. HttpChatService Improvement Plan

## Principle

- Agentic 요구사항 충족을 위해 기존 `HttpChatService`와 주변 계층을 직접 수정할 수 있다.
- API 계약 변경도 필요하면 허용한다.
- MCP 런타임(`mcp.*`)은 재사용 또는 재구성 모두 허용한다.

## Reuse Strategy

- `AgentOrchestrator`는 `ToolExecutionService`를 통해 MCP 실행을 위임하고, 실제 MCP 연동은 `McpToolExecutionService` + `McpClientFactory`에서 처리한다.
- 상태 관리는 `ConversationStore`/`GraphCheckpointStore` 기반 Redis 구조를 우선 사용한다.
- 모델 호출은 `Spring AI` 기반 공통 runtime으로 통합한다.

## Change Rules

- 기존 public 메서드 시그니처 변경 가능
- 신규 기능은 별도 패키지 추가 또는 기존 패키지 리팩토링 모두 허용
- 구조 패턴 적용 여부는 구현 난이도와 유지보수성 기준으로 결정

## Current Status

- `HttpChatService`는 `AgentOrchestrator` 위임 구조로 단순화 완료
- 예외 처리는 `GlobalExceptionHandler` + `ChatProcessingException`으로 분리 완료
- scoped 경로는 `ScopedAgentChatService` + `BaseAgentControllerSupport`로 공통화 완료
- A2A 경로는 `controller.a2a.*` + `A2aLifecycleService`로 core runtime 재사용 완료
- 남은 개선은 관찰성(trace), 테스트 안정화, 운영 로그 민감정보 축소 중심

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split 완료: `application.yml` imports `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key
