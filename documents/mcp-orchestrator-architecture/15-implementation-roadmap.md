# 15. Implementation Roadmap

## Phase 1: Baseline Agentic Layer

- 기존 `HttpChatController`를 Agentic 진입점으로 확장
- `AgentOrchestrator` 도입 (`LangGraph4j StateGraph` 실행 담당)
- `PlanningService`, `ToolExecutionService`, `ResponseComposeService` 분리
- Redis 기반 `ConversationStore`, `GraphCheckpointStore` 구현
- 기존 `HttpChatController`/`HttpChatService`는 유지 (병행 운영)
- 기본 노드: `plan -> execute -> compose -> persist`

## Phase 2: Reliable Orchestration

- conditional edge handoff (`LangGraph4j`)
- checkpoint resume + retry policy
- tool group 라우팅 정책 고도화 (플랫폼 중립 capability 기준)
- 인증/권한 실패 시 `HUMAN_MESSAGE` 표준화
- component 테스트 + graph 통합 테스트

## Phase 3: Production Hardening

- HITL(approval) 노드 도입
- observability 강화 (trace, token/cost, tool latency)
- 멀티 인스턴스 동시성 제어 (세션 락/버전)
- provider failover 및 degrade 전략
- Redis 고가용성(복제/센티널/클러스터) 운영 표준 반영

## Guardrail (All Phases)

- LLM call: step당 기본 1회 + 실패 시 1회 재시도
- tool allowlist 강제
- raw prompt/token/session 내부값 로그 금지
