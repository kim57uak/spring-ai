# 15. SpringAI To Supervisor Usecase Mapping

Last synchronized with source: 2026-04-18  
Source baseline: `src/main/java/com/example/springsupervisorai`

## Supervisor Runtime Mapping

- `SupervisorA2AController`
  - supervisor의 단일 A2A JSON-RPC 진입점이다.
  - unary, streaming, tasks, review endpoints를 하나의 컨트롤러에서 처리한다.
- `SupervisorA2ARequestValidator`
  - `jsonrpc`, `method`, `params` 계약 검증과 send/review params 해석을 담당한다.
- `SupervisorAgentService`
  - 현재 use case entry service다.
  - pre-HITL A2UI shortcut, HITL gate, execution, review delegation을 조합한다.
- `SupervisorPreHitlA2uiService`
  - planner 결과의 `__preHitlA2ui` 힌트를 기반으로 실행 전 입력 A2UI를 먼저 띄운다.
  - 현재 지원 템플릿:
    - `package_reservation_form`
    - `package_sale_product_create_form`
- `HitlGateService`
  - `HitlPolicyService`, `HitlDecisionService`, waiting-review task 생성을 묶는 application service다.
- `SupervisorExecutionService`
  - sync/stream/review-resume 실행 경계다.
- `SupervisorReviewApplicationService`
  - approve/cancel review 흐름과 재실행을 담당한다.
- `SupervisorAgentOrchestrator`
  - graph execution, compose, persistence, failure translation, task completion 반영을 총괄한다.
- `SupervisorGraphExecutionService`
  - history/swarm/checkpoint 로드 후 compiled graph를 실행하고 `SupervisorPlanningContext`를 복원한다.
- `LangGraphSupervisorStateGraphFactory`
  - 현재 graph node 순서:
    - `plan`
    - `select`
    - `invoke`
    - `handoff_evaluate`
    - `handoff_apply`
    - `merge`
    - `compose`
- `DefaultA2AInvocationService`
  - downstream agent 호출, retry/backoff, method fallback, per-agent circuit breaker를 담당한다.
- `LlmSupervisorResponseComposeService`
  - downstream 결과를 text 또는 A2UI payload로 합성한다.
- `DefaultSupervisorSwarmCoordinator`
  - swarm state 기반 soft cooldown, invocation/handoff event log, shared facts 관리를 담당한다.

## Current Usecases

- `pre-hitl-input-form`
  - 실행 전 추가 사용자 입력이 필요할 때 planner만으로 A2UI form을 먼저 노출
- `hitl-gated-mutation`
  - 데이터 변경성 요청을 review 대기로 전환
- `review-approve-resume`
  - 승인 후 기존 task를 같은 taskId로 재실행
- `review-cancel`
  - waiting-review task를 취소 상태로 종료
- `single-agent-route`
  - 하나의 downstream agent만 호출
- `multi-agent-route`
  - 여러 routing plan을 batch/loop로 실행
- `handoff-route`
  - downstream 결과의 handoff directive를 검증 후 routing plan queue에 삽입
- `compose-to-a2ui`
  - downstream 성공 결과를 A2UI payload로 변환 가능하면 text 대신 structured UI 응답 제공

## Current Storage / Lifecycle Mapping

- `SupervisorA2aLifecycleService`
  - task 생성, waiting-review 전이, running/completed/failed/canceled 관리
- `SupervisorTaskFacade`
  - lifecycle service 위 facade
- `SupervisorReviewStore`
  - review ticket persistence
- `SupervisorSwarmStateStore`
  - session/task 기반 swarm state persistence
- `ConversationStore`
  - supervisor history persistence
- `GraphCheckpointStore`
  - supervisor graph checkpoint persistence

## Current LLM Mapping

- `DefaultSupervisorLlmRuntime`
  - supervisor 계층의 공통 complete/stream 포트
- `LlmSupervisorPlanningService`
  - planning prompt + repair prompt 사용
- `LlmHitlPolicyService`
  - hitl policy JSON output + repair prompt 사용
- `LlmSupervisorResponseComposeService`
  - compose prompt + compose A2UI prompt + repair prompt 사용

## Source-backed Constraints

- supervisor는 `/a2a/supervisor`만 노출하며, 내부에서 다른 controller로 포워딩하지 않는다.
- pre-HITL A2UI가 있으면 HITL/실행보다 우선한다.
- streaming path는 `SupervisorOutputEvent`를 SSE `chunk/a2ui/done/error`로 직렬화한다.
- review decide는 unary와 stream 두 경로를 모두 지원한다.
