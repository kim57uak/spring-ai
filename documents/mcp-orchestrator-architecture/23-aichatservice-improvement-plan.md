# 23. HttpChatService Improvement Plan

## Principle

- Agentic 요구사항 충족을 위해 기존 `HttpChatService`와 주변 계층을 직접 수정할 수 있다.
- API 계약 변경도 필요하면 허용한다.
- MCP 런타임(`mcp.*`)은 재사용 또는 재구성 모두 허용한다.

## Reuse Strategy

- `AgentOrchestrator`는 `McpClientFactory`를 직접 사용하거나 구조에 맞게 리팩토링해서 사용한다.
- `SessionMemoryManager`는 Redis 기반 상태 관리 구조로 통합 변경할 수 있다.
- 모델 호출은 `Spring AI` 기반 공통 runtime으로 통합한다.

## Change Rules

- 기존 public 메서드 시그니처 변경 가능
- 신규 기능은 별도 패키지 추가 또는 기존 패키지 리팩토링 모두 허용
- 구조 패턴 적용 여부는 구현 난이도와 유지보수성 기준으로 결정
