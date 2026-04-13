# 17. Supervisor Package/Class Specification

## Recommended Package Structure

```text
src/main/java/com/example/springsupervisorai
├── config
│   ├── A2aSupervisorRoutingProperties
│   ├── SupervisorPromptProperties
│   └── SupervisorStreamProperties
├── controller
│   └── SupervisorA2AController
├── service
│   ├── SupervisorAgentService
│   ├── SupervisorAgentOrchestrator
│   └── SupervisorProgressSupport
├── service/agent
│   ├── plan/{SupervisorPlanningService, LlmSupervisorPlanningService}
│   ├── invoke/{A2AInvocationService, DefaultA2AInvocationService, A2AClientRegistry}
│   ├── handoff/{HandoffPolicyService, DefaultHandoffPolicyService}
│   ├── compose/{SupervisorResponseComposeService, LlmSupervisorResponseComposeService}
│   ├── graph/{SupervisorStateGraphFactory, LangGraphSupervisorStateGraphFactory}
│   ├── hitl/{HitlPolicyService, HitlDecisionService}
│   └── store/{ConversationStore, GraphCheckpointStore, SupervisorSwarmStateStore, SupervisorReviewStore}
├── a2a
│   ├── A2AJsonRpcClient
│   ├── A2ARequestMapper
│   └── A2AResponseMapper
└── model
    ├── SupervisorAgentRequest
    ├── SupervisorPlanningContext
    ├── SwarmSharedState
    ├── HitlReviewContext
    ├── RoutingPlan
    ├── DownstreamCallResult
    ├── HandoffDirective
    └── HandoffValidationResult
```

## Core Contracts

- `SupervisorPlanningService#plan(context): List<RoutingPlan>`
- `A2AInvocationService#invoke(plan, context): DownstreamCallResult`
- `HandoffPolicyService#evaluate(result, context): HandoffValidationResult`
- `HandoffPolicyService#apply(context, validation): SupervisorPlanningContext`
- `SupervisorResponseComposeService#streamCompose(context): Flux<String>`
- `HitlPolicyService#evaluate(context, plans): HitlReviewContext`
- `HitlDecisionService#decide(taskId, decision): HitlReviewContext`
- `SupervisorStateGraphFactory#getCompiledGraph(): CompiledGraph<SupervisorGraphState>`
- `SupervisorProgressSupport#line(stage, progress, message, metadata): String`

---

## 2026-04-13 동기화 메모 (34 반영)

- handoff 기능은 `service/agent/handoff` 패키지로 분리해 SOLID(단일 책임/의존 역전) 원칙을 유지한다.
- 진행상태/생각과정 출력은 `SupervisorProgressSupport` 공통 모듈을 사용한다.
- handoff method는 기존 허용 enum만 통과시키고, stream 미지원 agent 대상 stream handoff는 차단한다.
- 신규/수정 public 타입과 핵심 메서드는 Javadoc 필수 적용 대상으로 관리한다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
