# 12. Supervisor Agent Technology Decision

## Final Choice

- `Spring AI + LangGraph4j + Redis + A2A(JSON-RPC/SSE)`

## Why

- `Spring AI`: supervisor agent planning/compose에 필요한 모델 추상화 유지
- `LangGraph4j`: 라우팅/호출/병합 흐름을 상태그래프로 강제
- `Redis`: supervisor 세션 히스토리/체크포인트 외부화
- `A2A`: 하위 에이전트 내부 구현과 분리된 안정 경계 제공

## Scope

- Supervisor agent는 하위 에이전트를 A2A로만 호출한다.
- 하위 에이전트 내부 로직/툴/MCP는 설계 범위에서 제외한다.
- 기존 프로젝트의 LLM 호출 정책(`LlmCallPolicy`, rate-limit)과 동일 원칙을 사용한다.

