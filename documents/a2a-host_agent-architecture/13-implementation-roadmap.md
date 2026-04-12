# 13. Supervisor Agent Implementation Roadmap

## Phase 1: Supervisor Core

- `SupervisorA2AController` 단일 진입점 추가
- `SupervisorAgentService -> SupervisorAgentOrchestrator` 기본 흐름 구현
- `SupervisorPlanningService`, `SupervisorResponseComposeService` 분리

## Phase 2: LangGraph Supervisor Flow

- `plan -> select -> invoke -> merge -> compose` 그래프 구현
- iteration guard 및 checkpoint resume 적용

## Phase 3: A2A Routing

- `A2AClientRegistry` + `A2AJsonRpcClient` 구현
- downstream agent allowlist/timeout/retry/circuit-breaker 반영
- `a2a-supervisor.yml` 기반 라우팅 설정 외부화

## Phase 4: Production Hardening

- observability(호출 지연, 실패율, 토큰/비용) 강화
- 대체 전략(fallback/partial result) 추가
- 회귀 테스트 + 계약 테스트 자동화
