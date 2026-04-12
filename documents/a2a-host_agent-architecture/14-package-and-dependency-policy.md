# 14. Supervisor Agent Package And Dependency Policy

## Base Package

- `com.example.springsupervisorai`

## Dependency Rules

- `controller -> service -> orchestrator -> ports(plan/invoke/compose/store/graph)`
- `controller -> service -> orchestrator -> ports(plan/invoke/compose/hitl/store/graph)`
- `invoke -> a2a client/registry` only
- `plan/compose -> runtime(DefaultSupervisorLlmRuntime)` only
- 상위 계층은 구현체가 아니라 interface에 의존한다.

## Design Rules

- Host는 하위 agent 내부 계약에 직접 의존하지 않는다.
- 하위 agent 호출은 반드시 A2A endpoint allowlist를 통과해야 한다.
- 실패 응답은 supervisor 표준 메시지로 정규화한다.
- supervisor 진입점은 `/a2a/supervisor`(stream 포함)만 사용한다.
- HITL 결정은 `approve/cancel` 인터페이스로 분리하고 orchestrator에서만 조합한다.
- Swarm shared state는 `SupervisorSwarmStateStore` 포트를 통해서만 접근한다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
