# 15. Implementation Roadmap

## Phase 1: Baseline Agentic Layer

- 완료: `HttpChatController -> HttpChatService -> AgentOrchestrator` 흐름 정착
- 완료: `PlanningService`, `ToolExecutionService`, `ResponseComposeService` 분리
- 완료: Redis 기반 `ConversationStore`, `GraphCheckpointStore` 운영
- 완료: `LangGraphAgentStateGraphFactory` 기반 그래프 실행
- 완료: 기본 흐름 `plan -> execute -> compose -> persist`

## Phase 2: Reliable Orchestration

- 완료: conditional edge 분기(`plan` 결과에 따라 `execute`/`compose` 분기)
- 완료: checkpoint load/save 및 session clear 연동
- 완료: capability 기반 tool routing + scope filter 적용
- 완료: 예외/실패 시 사용자 메시지 표준화(`HumanMessageService`)
- 진행중: component 테스트 + graph 통합 테스트 확장

## Phase 3: Production Hardening

- 계획: HITL(approval) 노드 도입
- 계획: observability 강화(trace, token/cost, tool latency)
- 계획: 멀티 인스턴스 동시성 제어(세션 락/버전)
- 계획: provider failover 및 degrade 전략
- 계획: Redis 고가용성(복제/센티널/클러스터) 운영 표준 반영

## Phase 4: A2A Core Integration (Current Version Baseline)

- 완료: `/.well-known/agent.json`, `/a2a/{scope}` endpoint 추가
- 완료: `Product/Reservation/SearchA2AController` 추가(기존 `/api/*` 유지)
- 완료: `A2ATaskStore` 도입(`message/send`, `message/stream`, `tasks/get`, `tasks/cancel`, `tasks/list`)
- 완료: `ScopedAgentChatService`/`AgentOrchestrator` A2A lifecycle 연동
- 완료: scope ownership 검증(교차 scope task 접근 차단)
- 진행중: A2A 회귀/호환성 테스트 케이스 보강

## Guardrail (All Phases)

- LLM call: `LlmCallPolicy` + `llm.rate-limit.*` 설정 기반으로 최소 간격/재시도/백오프를 적용
- tool allowlist 강제
- raw prompt/token/session 내부값 로그 금지(단, tool payload preview 로그는 추가 축소 필요)
- 예외 응답은 `GlobalExceptionHandler`에서 일원화
- A2A 도입 시에도 기존 `/api/*` 계약/동작 변경 금지

## 2026-04-11 Implementation Sync

- `controller.base.*` + `controller.a2a.*` 구조로 분리 완료
- `mcp.yml` 분리 완료(`application.yml`에서 import)
- `ToolSchemaRegistry`는 reconnect-first + cache fallback 전략으로 동작
- 현재 버전 라인 유지: Spring AI `1.0.3`, LangGraph4j `1.8.10`, A2A SDK BOM `1.0.0.Alpha4`
