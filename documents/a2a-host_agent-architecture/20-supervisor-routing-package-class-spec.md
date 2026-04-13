# 20. Supervisor Routing Package/Class Spec

## Scope

- 진입점은 `SupervisorA2AController` 단일 경로(`/a2a/supervisor`)만 사용한다.
- 하위 에이전트 연동은 `A2AInvocationService`를 통해서만 수행한다.
- A2A 메서드는 `legacy + v1.0` 동시 지원을 기본 계약으로 유지한다.
- HITL 결정은 이번 단계에서 `APPROVE/CANCEL`만 지원한다.
- shared context는 `SupervisorSwarmStateStore`를 통해 관리한다.
- handoff는 feature flag(`handoff.enabled`)로 on/off 가능해야 한다.
- handoff method는 기존 허용 enum만 허용하며, stream 미지원 agent 대상 stream handoff는 금지한다.

## Core Classes

- `SupervisorA2AController`
- `SupervisorAgentService`
- `SupervisorAgentOrchestrator`
- `SupervisorPlanningService`
- `A2AInvocationService`
- `SupervisorResponseComposeService`
- `HitlPolicyService`
- `HitlDecisionService`
- `HandoffPolicyService`
- `A2AClientRegistry`
- `A2AJsonRpcClient`
- `SupervisorProgressSupport`

## Core Contracts

- `SupervisorPlanningService#plan(context): List<RoutingPlan>`
- `A2AInvocationService#invoke(plan, context): DownstreamCallResult`
- `HandoffPolicyService#evaluate(result, context): HandoffValidationResult`
- `SupervisorResponseComposeService#streamCompose(context): Flux<String>`
- `HitlPolicyService#evaluate(context, plans): HitlReviewContext`
- `HitlDecisionService#decide(taskId, decision): HitlReviewContext`
- `SupervisorProgressSupport#line(stage, progress, message, metadata): String`

---

## 2026-04-13 동기화 메모 (34 반영)

- `invoke -> handoff_evaluate -> (handoff_apply|handoff_skip) -> merge` 분기 흐름을 routing 사양에 반영한다.
- `handoff.enabled=false`일 때 기존 plan 소비 경로와 동등 동작을 보장한다.
- 진행상태 출력은 공통 모듈(`SupervisorProgressSupport`) 사용을 기본 규칙으로 한다.
- 신규/수정 public 타입과 핵심 메서드에는 Javadoc을 필수로 적용한다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
