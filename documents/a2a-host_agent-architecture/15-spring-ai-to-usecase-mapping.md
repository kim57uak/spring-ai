# 15. Spring AI To Supervisor Usecase Mapping

## Supervisor Runtime Mapping

- `SupervisorPlanningService`
  - 사용자 질의에서 downstream agent 라우팅 계획을 생성
- `HitlPolicyService`
  - 라우팅 계획과 위험 신호를 평가해 human review 필요 여부를 판정
- `HitlDecisionService`
  - 이번 단계에서 `APPROVE/CANCEL` 결정만 반영
- `SupervisorSwarmStateStore`
  - review 상태, 공유 facts, event log를 버전 기반으로 저장/복원
- `A2AInvocationService`
  - 계획에 따라 downstream A2A 호출 실행
- `HandoffPolicyService`
  - invoke 결과의 handoff directive를 검증하고 동적 routing plan 삽입 여부를 결정
- `SupervisorResponseComposeService`
  - 다중 downstream 결과를 최종 응답으로 합성
- `SupervisorProgressSupport`
  - 생각 과정 UI 표시를 위한 공통 progress/trace 포맷 생성
- `DefaultSupervisorLlmRuntime`
  - planning/compose 공통 모델 호출 포트
- `LlmCallPolicy`
  - 재시도/백오프/rate-limit 정책 통합

## Usecase

- `single-agent route`
  - 한 downstream agent만 호출해 결과 반환
- `multi-agent route`
  - 복수 downstream agent 호출 후 병합 응답 생성
- `fallback route`
  - 특정 agent 실패 시 제한된 partial result로 응답
- `mandatory hitl route`
  - 상품/예약/주문 생성·변경 요청은 자동 실행 없이 review 대기 후 승인 시 진행
- `handoff route (feature-flagged)`
  - `handoff.enabled=true`일 때만 invoke 이후 다음 agent로 동적 이관을 허용
  - `handoff.enabled=false`면 기존 정적 plan 소비 경로 유지
- `handoff guard route`
  - handoff method는 허용 enum만 통과
  - stream 미지원 agent로의 stream handoff는 차단

---

## 2026-04-13 동기화 메모 (34 반영)

- handoff는 supervisor 그래프의 invoke 이후 분기로 추가되며 feature flag(`handoff.enabled`)로 즉시 on/off 가능해야 한다.
- handoff 적용 여부/사유/hopCount는 `SupervisorProgressSupport` 공통 포맷과 swarm event log로 함께 기록한다.
- handoff method는 기존 허용 enum만 사용하고, downstream streaming capability 없는 agent 대상 stream handoff는 금지한다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
