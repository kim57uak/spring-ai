# 15. Implementation Roadmap

## Phase 1: Baseline Agentic Layer

- 완료: `HttpChatController -> HttpChatService -> AgentOrchestrator` 흐름 정착
- 완료: `PlanningService`, `ToolExecutionService`, `ResponseComposeService` 분리
- 완료: Redis 기반 `ConversationStore`, `GraphCheckpointStore` 운영
- 완료: `LangGraphAgentStateGraphFactory` 기반 그래프 실행
- 완료: 기본 흐름 `plan -> execute -> compose -> persist`

## Phase 2: Reliable Orchestration

- 진행중: conditional edge handoff (`LangGraph4j`)
- 진행중: checkpoint resume + retry policy 고도화
- 진행중: capability 기반 tool 라우팅 정책 정밀화
- 진행중: 예외/실패 시 사용자 메시지 표준화(`HumanMessageService`)
- 예정: component 테스트 + graph 통합 테스트

## Phase 3: Production Hardening

- HITL(approval) 노드 도입
- observability 강화 (trace, token/cost, tool latency)
- 멀티 인스턴스 동시성 제어 (세션 락/버전)
- provider failover 및 degrade 전략
- Redis 고가용성(복제/센티널/클러스터) 운영 표준 반영

## Guardrail (All Phases)

- LLM call: `LlmCallPolicy` + `llm.rate-limit.*` 설정 기반으로 최소 간격/재시도/백오프를 적용
- tool allowlist 강제
- raw prompt/token/session 내부값 로그 금지
- 예외 응답은 `GlobalExceptionHandler`에서 일원화
