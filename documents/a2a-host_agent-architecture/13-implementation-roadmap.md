# 13. Supervisor Agent Implementation Roadmap

## 30/31 통합 반영 로드맵

## Phase 0: HITL MVP Scope Freeze

- 결정 타입 `APPROVE/CANCEL`만 구현
- `tasks/review/get`, `tasks/review/decide` 계약 추가
- 데이터 생성/변경 요청 강제 HITL 정책 우선 반영

## Phase 1: Supervisor Core

- `SupervisorA2AController` 단일 진입점 추가
- `SupervisorAgentService -> SupervisorAgentOrchestrator` 기본 흐름 구현
- `SupervisorPlanningService`, `SupervisorResponseComposeService` 분리

## Phase 2: LangGraph Supervisor Flow

- `plan -> risk_assess -> hitl_gate -> wait_review -> apply_review -> select -> invoke -> merge -> compose` 그래프 구현
- iteration guard 및 checkpoint resume 적용
- Graph 상태와 Swarm shared state의 동시 저장/복원 정책 적용

## Phase 3: A2A Routing

- `A2AClientRegistry` + `A2AJsonRpcClient` 구현
- downstream agent allowlist/timeout/retry/circuit-breaker 반영
- `a2a-supervisor.yml` 기반 라우팅 설정 외부화
- `legacy + v1.0` 메서드 호환 모드 유지(enum 기반)

## Phase 4: Production Hardening

- observability(호출 지연, 실패율, 토큰/비용) 강화
- 대체 전략(fallback/partial result) 추가
- 회귀 테스트 + 계약 테스트 자동화

## Phase 5: Handoff Rollout (Doc 34)

- `invoke -> handoff_evaluate -> handoff_apply/skip -> merge` 그래프 분기 반영
- `handoff.enabled=false` 기본 배포 후 canary 세션 점진 활성화
- handoff 가드레일 적용
  - method enum allowlist
  - stream 지원 capability 검증
  - maxHops/duplicate path/rate-limit
- 진행상태를 `SupervisorProgressSupport` 공통 포맷으로 출력
- Javadoc + 단위/통합/회귀 테스트 완료

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.

---

## 2026-04-13 동기화 메모 (34 반영)

- handoff 기능은 기능 플래그 기반 점진 배포를 기본 전략으로 채택한다.
- SOLID/추상화/가독성/유지보수성 기준을 코드리뷰 게이트로 포함한다.
