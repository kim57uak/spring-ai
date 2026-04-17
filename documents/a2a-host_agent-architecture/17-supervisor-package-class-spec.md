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
│   ├── SupervisorExecutionService
│   ├── SupervisorExecutionResultCollector
│   ├── SupervisorReviewApplicationService
│   ├── SupervisorStreamProgressService
│   ├── SupervisorTaskFacade
│   ├── HitlGateService
│   ├── SupervisorProgressPublisher
│   ├── SupervisorExecutionPersistenceService
│   ├── SupervisorExecutionStateLoader
│   ├── SupervisorExceptionTranslator
│   ├── SupervisorFallbackInvokeService
│   ├── SupervisorGraphExecutionService
│   ├── SupervisorExecutionSummaryEmitter
│   ├── SupervisorHandoffProgressSupport
│   ├── SupervisorOutputEventSupport
│   └── SupervisorProgressSupport
├── service/agent
│   ├── plan/{SupervisorPlanningService, LlmSupervisorPlanningService}
│   ├── invoke/{A2AInvocationService, DefaultA2AInvocationService, A2AClientRegistry}
│   ├── handoff/{HandoffPolicyService, DefaultHandoffPolicyService}
│   ├── compose/{SupervisorResponseComposeService, LlmSupervisorResponseComposeService, ComposeOutcomeAnalyzer, ComposePromptBuilder, A2uiDecisionParser}
│   ├── graph/{SupervisorStateGraphFactory, LangGraphSupervisorStateGraphFactory, SupervisorGraphStateMapper, SupervisorGraphInputBuilder, SupervisorBatchExecutionPolicy, SupervisorPlanRunner}
│   ├── hitl/{HitlPolicyService, HitlDecisionService}
│   ├── a2ui/product/{DefaultSupervisorProductInfoA2uiService, ProductPayloadExtractor, ProductA2uiDataMapper, ProductA2uiMessageBuilder}
│   └── store/{ConversationStore, GraphCheckpointStore, SupervisorSwarmStateStore, SupervisorReviewStore}
├── a2a
│   ├── A2AJsonRpcClient
│   ├── A2ARequestMapper
│   └── A2AResponseMapper
└── model
    ├── SupervisorAgentRequest
    ├── SupervisorExecutionRequest
    ├── SupervisorGraphSnapshot
    ├── HitlPolicyContext
    ├── HandoffPolicyContext
    ├── InvocationPolicyContext
    ├── SupervisorOutputEvent
    ├── SupervisorOutputEventType
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
- `SupervisorResponseComposeService#streamComposeEvents(context): Flux<SupervisorOutputEvent>`
- `HitlGateService#evaluate(sessionId, message, model): HitlPolicyResult`
- `HitlPolicyService#evaluate(context): HitlPolicyResult`
- `HandoffPolicyService#evaluate(context): List<HandoffValidationResult>`
- `A2AInvocationService#invoke(context): DownstreamCallResult`
- `HitlGateService#openReview(sessionId, message, model, policy): A2aTaskSnapshot`
- `HitlGateService#decide(taskId, sessionId, decision, reason, decisionId): Optional<HitlReviewTicket>`
- `SupervisorReviewApplicationService#decideReview(sessionId, taskId, decision, reason, decisionId): Optional<Map<String, Object>>`
- `SupervisorExecutionService#executeSync(request): A2aTaskSnapshot`
- `SupervisorExecutionService#executeStream(request): Flux<String>`
- `SupervisorExecutionService#executeStreamEvents(request): Flux<SupervisorOutputEvent>`
- `SupervisorExecutionService#resumeApprovedTask(taskId, request): void`
- `SupervisorExecutionResultCollector#collect(events): SupervisorExecutionResult`
- `SupervisorTaskFacade#createRunningTask(sessionId, requestMessage): A2aTaskSnapshot`
- `SupervisorStreamProgressService#initialHitlEvaluationEvents(sessionId): Flux<SupervisorOutputEvent>`
- `SupervisorStreamProgressService#hitlRequiredEvents(policyResult, waitingTask): Flux<SupervisorOutputEvent>`
- `SupervisorStreamProgressService#hitlPassedEvents(): Flux<SupervisorOutputEvent>`
- `SupervisorProgressPublisher#emit(sink, stage, progress, message, metadata): void`
- `SupervisorProgressPublisher#emitEvent(sink, stage, progress, message, metadata): void`
- `SupervisorProgressPublisher#recordProgress(taskId, sessionId, nodeType, stage, progress, message, metadata): void`
- `SupervisorProgressPublisher#recordEvent(taskId, sessionId, nodeType, message, metadata): void`
- `SupervisorExecutionPersistenceService#persistCompletion(context, answer): void`
- `SupervisorExecutionStateLoader#load(sessionId): LoadedState`
- `SupervisorExecutionStateLoader#resolveCheckpointId(sessionId): String`
- `SupervisorExceptionTranslator#composeFailure(error): Failure`
- `SupervisorExceptionTranslator#orchestrationFailure(error): Failure`
- `SupervisorFallbackInvokeService#invokeIfRequired(request, taskId, canceled, context, progressCallback, cancellationChecker): void`
- `SupervisorGraphExecutionService#execute(request, taskId, canceled, progressCallback, cancellationChecker): GraphExecutionResult`
- `SupervisorAgentOrchestrator#executeEvents(request, taskId): Flux<SupervisorOutputEvent>`
- `SupervisorExecutionSummaryEmitter#emitGraphCompletion(context, snapshot, reporter): void`
- `SupervisorHandoffProgressSupport#metadata(snapshot, handoffPlanCount, totalPlanCount): Map<String, Object>`
- `SupervisorGraphStateMapper#toPlanningContext(state): SupervisorPlanningContext`
- `SupervisorGraphInputBuilder#buildInitialInput(request, taskId, loadedState): Map<String, Object>`
- `SupervisorGraphStateMapper#toPlanList(plans): List<Map<String, Object>>`
- `SupervisorBatchExecutionPolicy#resolveBatch(context, fromIndex): List<RoutingPlan>`
- `SupervisorPlanRunner#invokeBatch(batch, context): List<DownstreamCallResult>`
- `ComposeOutcomeAnalyzer#summarize(context): ComposeOutcomeSummary`
- `ComposePromptBuilder#buildComposePrompt(context, outcomeSummary): String`
- `A2uiDecisionParser#parse(raw): ComposeA2uiDecision`
- `SupervisorOutputEventSupport#serialize(event): String`
- `ProductPayloadExtractor#extractProductNode(payload): Optional<JsonNode>`
- `ProductA2uiDataMapper#map(productRoot, template): Optional<ProductPresentationModel>`
- `ProductA2uiMessageBuilder#build(surfaceId, model, template): List<Map<String, Object>>`
- `SupervisorStateGraphFactory#getCompiledGraph(): CompiledGraph<SupervisorGraphState>`
- `SupervisorProgressSupport#line(stage, progress, message, metadata): String`

---

## 2026-04-17 동기화 메모 (38 P0 반영)

- `SupervisorAgentService`는 use case orchestration만 담당하도록 축소하고, HITL과 실행 재개를 직접 구현하지 않는다.
- `HitlGateService`는 `HitlPolicyService`, `HitlDecisionService`, task waiting-review 전이를 묶는 application service로 동작한다.
- `SupervisorExecutionService`는 send/stream/review-resume 실행 경계를 공통 request 모델(`SupervisorExecutionRequest`)로 정리한다.
- `SupervisorExecutionResultCollector`는 sync/resume payload와 stream progress 문자열 경계를 분리한다.
- `SupervisorReviewApplicationService`는 review approve/cancel 유스케이스를 `SupervisorAgentService`에서 분리한다.
- `SupervisorStreamProgressService`는 stream 시작부 HITL 안내 이벤트를 전담한다.
- `SupervisorTaskFacade`는 `SupervisorA2aLifecycleService` 위에 task 생성/상태 전이 facade를 제공한다.
- `SupervisorAgentOrchestrator`는 progress emit, completion persistence, fallback invoke를 각각 `SupervisorProgressPublisher`, `SupervisorExecutionPersistenceService`, `SupervisorFallbackInvokeService`로 위임한다.
- `SupervisorGraphExecutionService`는 history/swarm/checkpoint 로드, graph input 구성, graph invoke, context 복원을 담당한다.
- `SupervisorGraphSnapshot`, `SupervisorGraphStateMapper`는 graph 상태의 typed snapshot/직렬화 변환 책임을 담당한다.
- `SupervisorGraphInputBuilder`는 graph 시작 입력을 typed snapshot 기반으로 조립한다.
- `LlmSupervisorResponseComposeService`는 facade 역할로 축소하고, outcome 분석/프롬프트 조립/A2UI decision parsing은 각각 별도 클래스로 분리한다.
- `DefaultSupervisorProductInfoA2uiService`는 facade로 유지하고, payload 추출/도메인 정규화/A2UI 메시지 조립은 각각 `ProductPayloadExtractor`, `ProductA2uiDataMapper`, `ProductA2uiMessageBuilder`로 분리한다.
- supervisor 내부 스트림은 `SupervisorOutputEvent`로 정규화하고, 외부 `Flux<String>` 계약은 `SupervisorOutputEventSupport` adapter로 유지한다.
- `SupervisorA2AController`는 service가 반환한 `SupervisorOutputEvent`를 SSE event/data로 매핑하는 transport adapter 역할을 담당한다.
- `SupervisorProgressPublisher`는 user-facing progress와 swarm event log 기록의 단일 진입점으로 사용한다.
- `SupervisorAgentOrchestrator`는 progress 발행 시 `taskId/sessionId/nodeType` 문맥을 `SupervisorProgressPublisher`에 함께 전달한다.
- `SupervisorExecutionSummaryEmitter`는 graph completion/routing summary/graph invocation summary 출력을 오케스트레이터에서 분리한다.
- `SupervisorHandoffProgressSupport`는 handoff metadata 조립을 snapshot 기반 helper로 담당한다.
- `LangGraphSupervisorStateGraphFactory`의 invoke node는 batch 선택/실행 세부를 직접 구현하지 않고 `SupervisorBatchExecutionPolicy`, `SupervisorPlanRunner`에 위임한다.
- `SupervisorFallbackInvokeService`도 동일한 `SupervisorBatchExecutionPolicy`, `SupervisorPlanRunner`를 재사용해 graph/fallback 실행 규칙을 맞춘다.
- HITL/handoff 정책 입력은 primitive 인자 대신 `HitlPolicyContext`, `HandoffPolicyContext`로 명시화한다.
- downstream invocation 입력도 `InvocationPolicyContext`로 명시화한다.
- 상태 복원과 checkpoint 검증은 `SupervisorExecutionStateLoader`로 분리하고, orchestration/compose 실패 정규화는 `SupervisorExceptionTranslator`가 담당한다.
- `REVISE` decision과 추가정보 수집은 다음 단계에서 별도 application service와 typed amendment model로 확장 가능한 구조를 전제한다.

## 2026-04-17 P2 설계 메모 (`REVISE` / 추가정보 수집)

- `REVISE`는 reviewer가 실행 파라미터 또는 실행 계획을 수정해 재실행을 요청하는 흐름으로 정의한다.
- 구현 경계는 `SupervisorReviewApplicationService` 아래에 `SupervisorReviewRevisionService`를 추가해 approve/cancel과 분리한다.
- 핵심 typed model 후보:
  - `HitlRevisionRequest`
  - `HitlRevisionPatch`
  - `SupervisorExecutionAmendment`
  - `MissingInfoRequest`
  - `MissingInfoAnswer`
- `REVISE`는 `WAITING_REVIEW -> REVISED -> RUNNING` 전이를 거쳐 amended execution request로 재실행한다.
- 추가정보 수집은 review decision이 아니라 execution precondition 충족으로 분리하며, `SupervisorExecutionService` 진입 전에 해결하는 것을 원칙으로 한다.
- 추가정보 수집 단계는 자연어/콤마 텍스트 입력을 허용하되, 내부에서는 structured field map으로 정규화한다.
- transport 계층은 필요한 경우 A2UI surface를 재사용할 수 있지만, 내부 domain contract는 A2UI 여부와 분리한다.

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
