# 38. Supervisor 책임 분리 중심 리팩토링 계획서

## 1) 목적과 범위

본 문서는 현재 `com.example.springsupervisorai` 코드베이스를 기준으로 Supervisor 영역을 "기능 추가"보다 "책임 분리" 중심으로 재구성하기 위한 리팩토링 계획을 정의한다.

- 목적
  - `SupervisorAgentService`, `SupervisorAgentOrchestrator`, Graph, Compose, A2UI 영역의 책임 경계를 선명하게 만든다.
  - `send/stream/review resume` 흐름을 읽기 쉽게 만들고 테스트 단위를 작게 쪼갠다.
  - Map/문자열 중심 상태 모델을 typed object 중심으로 점진 전환한다.
  - progress, event log, task lifecycle, A2UI payload 조립의 횡단 책임을 각각 독립된 모듈로 정리한다.
- 범위
  - `service`
  - `service/agent/graph`
  - `service/agent/compose`
  - `service/agent/a2ui`
  - `model`
  - `config`
- 제외
  - 하위 agent 자체 비즈니스 기능 추가
  - 외부 A2A 프로토콜 스펙 변경
  - 프론트엔드 렌더러의 대규모 UX 재설계

---

## 2) 현재 구조 진단 요약

현재 구현은 동작 자체는 성립하지만, 여러 계층에서 orchestration 관련 책임이 중첩되어 있다.

### A. Service 계층의 책임 혼합

`SupervisorAgentService`는 아래 책임을 함께 가지고 있다.

- API use case 진입점
- HITL 평가
- waiting review task 생성
- send 동기 집계
- stream 비동기 분기
- review approve/cancel 처리
- approve 후 실행 재개 및 완료 처리

즉, "요청 진입", "정책 게이트", "실행 위임", "task 상태 전이"가 분리되지 않았다.

### B. Orchestrator 계층의 책임 과밀

`SupervisorAgentOrchestrator`는 아래를 동시에 수행한다.

- 그래프 실행 시작/복원
- history/swarm/checkpoint 로드
- progress 문자열 생성 및 emit
- graph 결과 해석
- fallback invoke
- compose stream 처리
- 최종 persistence
- task 완료/실패 반영
- swarm event log 기록

이는 파이프라인 coordinator를 넘어 execution application service + observability + persistence adapter 역할까지 같이 들고 있는 상태다.

### C. Graph 상태 모델의 Map 의존

`SupervisorGraphState`와 `LangGraphSupervisorStateGraphFactory`는 다수의 문자열 key와 `Map<String, Object>` 직렬화/역직렬화에 의존한다.

- 상태 필드 추가 시 문자열 key 누락 위험이 큼
- graph node가 비즈니스 전이보다 데이터 포맷 변환 코드로 길어짐
- handoff/HITL/A2UI가 늘수록 유지보수 비용이 급격히 증가함

### D. 사용자 progress와 운영 event log의 이원화

현재 progress는 일부는 `SupervisorAgentOrchestrator`에서 직접 emit되고, 일부는 graph 내부에서 `swarmCoordinator.recordNodeEvent(...)`로만 기록된다.

결과:

- 사용자 스트림과 운영 로그가 같은 실행을 서로 다르게 표현함
- 문제 발생 시 trace 상관관계 파악이 어려움
- 신규 stage 추가 시 누락 가능성이 큼

### E. Compose/A2UI 클래스의 응집도 저하

`LlmSupervisorResponseComposeService`는 아래를 함께 수행한다.

- downstream outcome 판정
- compose prompt 조립
- A2UI 여부 판단
- A2UI JSON 파싱/repair
- fallback summary 생성

`DefaultSupervisorProductInfoA2uiService`는 아래를 함께 수행한다.

- payload JSON 탐색
- product node 추출
- required field 검증
- domain data 정규화
- A2UI protocol message 조립
- component tree 생성

즉, "의사결정", "데이터 추출", "표현 모델 변환", "프로토콜 직렬화"가 한 클래스 안에 섞여 있다.

---

## 3) 리팩토링 목표 아키텍처

핵심 목표는 Supervisor를 아래 5개 축으로 재구성하는 것이다.

1. API use case orchestration
- 요청 진입과 응답 조립만 담당

2. execution gating
- HITL, invocation, handoff, A2UI 정책 판정을 독립 서비스로 분리

3. execution pipeline
- "실행 가능한 요청"만 graph/orchestrator로 위임

4. typed state and typed output
- graph 상태와 사용자 스트림을 더 명시적인 타입으로 관리

5. presentation assembly
- compose와 A2UI를 domain extraction / outcome analysis / payload assembly로 분해

원칙:

- 리팩토링은 외부 계약을 가급적 유지한 채 내부 책임만 재배치한다.
- 상태와 이벤트는 primitive보다 value object를 우선 사용한다.
- progress/event/task completion은 동일 실행 단위를 기준으로 함께 추적 가능해야 한다.
- 신규 기능 추가보다 테스트 가능성과 변경 용이성을 먼저 확보한다.

---

## 4) TO-BE 구조 제안

### A. Service 경계 재구성

#### 1. `SupervisorAgentService`

역할:

- controller/use case 진입점
- request validation 이후 application flow 조립
- response mapper 호출

제외할 책임:

- HITL 정책 평가 세부 구현
- review open/approve/cancel 상세 처리
- task 상태 전이 세부 구현
- orchestration payload 수집/재개 처리

#### 2. `HitlGateService`

역할:

- HITL 평가
- review open
- review approve
- review cancel
- HITL 통과/대기/거절 결과를 typed result로 반환

예상 계약:

- `evaluateForSend(...)`
- `evaluateForStream(...)`
- `openReview(...)`
- `approveReview(...)`
- `cancelReview(...)`

#### 3. `SupervisorExecutionService`

역할:

- "실행 가능한 supervisor 요청"을 오케스트레이터에 위임
- send/stream/resume 모드 차이를 내부 전략으로 처리
- sync payload 수집, stream cancellation 처리, resume 흐름 통일

예상 계약:

- `executeSync(SupervisorExecutionRequest request)`
- `executeStream(SupervisorExecutionRequest request)`
- `resumeApprovedTask(SupervisorExecutionRequest request)`

#### 4. `SupervisorTaskFacade`

역할:

- task 생성
- waiting/running/completed/failed/canceled 전이
- task 조회/list/cancel

효과:

- application service가 lifecycle service 세부 구현에 덜 의존
- 테스트에서 상태 전이 assertion이 단순해짐

### B. Orchestrator 경계 재구성

`SupervisorAgentOrchestrator`는 최종적으로 아래 책임만 유지하는 것이 바람직하다.

- execution pipeline 조정
- graph runner 호출
- compose runner 호출
- 취소/예외 경계 관리

분리 대상:

- `SupervisorProgressPublisher`
  - progress emit
  - swarm event log 동시 기록
- `SupervisorExecutionPersistenceService`
  - history 저장
  - checkpoint 저장/삭제
  - compose 완료 후 persistence 처리
- `SupervisorGraphExecutionService`
  - history/swarm/checkpoint 로드
  - graph input 구성
  - graph invoke
  - context 복원
- `SupervisorFallbackInvokeService`
  - graph 결과가 비어 있을 때 fallback invoke 수행

### C. Graph 상태 typed object 전환

#### 1. 신규 typed snapshot 도입

예상 타입:

- `SupervisorGraphSnapshot`
- `SupervisorGraphInput`
- `SupervisorGraphBatchResult`
- `SupervisorHandoffSnapshot`

역할:

- 현재 graph state의 주요 필드를 typed access로 노출
- graph node가 key 문자열을 직접 만지지 않도록 보조

#### 2. Mapper 분리

예상 타입:

- `SupervisorGraphStateMapper`
- `RoutingPlanStateMapper`
- `DownstreamResultStateMapper`
- `HandoffValidationStateMapper`

역할:

- `toPlanMap`, `toResultList`, `readResults` 등 변환 코드를 전담
- Graph factory와 `SupervisorGraphState`의 중복 변환 로직 제거

#### 3. Graph node 축소

목표:

- graph node는 "상태 읽기/쓰기"보다 "비즈니스 전이"만 수행
- 상태 직렬화와 이벤트 메타데이터 생성은 helper/service로 위임

### D. Progress / event log 단일화

#### 1. `SupervisorProgressPublisher`

책임:

- 사용자용 progress event 발행
- swarm event log 기록
- stage/progress/message/metadata 규격 통일

출력 대상:

- user-facing progress
- operational event log

규칙:

- graph node는 `recordNodeEvent(...)`를 직접 호출하지 않고 publisher만 사용
- orchestrator와 graph가 같은 stage taxonomy를 사용

#### 2. typed output event 도입

현재 `Flux<String>`는 아래가 모두 문자열로 섞여 있다.

- progress line
- 일반 텍스트 chunk
- A2UI wrapped payload
- 오류 메시지

중장기적으로는 아래 타입 도입이 필요하다.

- `SupervisorOutputEvent`
  - `PROGRESS`
  - `TEXT_CHUNK`
  - `A2UI_PAYLOAD`
  - `ERROR`

1차 단계에서는 내부 타입만 먼저 도입하고, 외부 SSE는 마지막에 문자열로 변환하는 adapter를 둔다.

### E. Compose/A2UI 분해

#### 1. Compose 영역

`LlmSupervisorResponseComposeService` 분리 대상:

- `ComposeOutcomeAnalyzer`
  - downstream 결과 판정
  - failure-only 여부 판단
- `ComposePromptBuilder`
  - prompt variable 조립
  - prompt render 호출
- `A2uiDecisionParser`
  - compose A2UI JSON 파싱
  - repair fallback
- `ComposeFallbackMessageBuilder`
  - deterministic fallback/failure summary 생성

#### 2. Product A2UI 영역

`DefaultSupervisorProductInfoA2uiService` 분리 대상:

- `ProductPayloadExtractor`
  - payload JSON 파싱
  - product node 탐색
- `ProductA2uiDataMapper`
  - raw JSON -> `ProductPresentationModel`
- `ProductA2uiMessageBuilder`
  - protocol message sequence 조립
- `ProductComponentTreeBuilder`
  - component adjacency list 생성

중간 모델:

- `ProductPresentationModel`

효과:

- payload 구조 변경과 renderer 변경의 영향을 분리
- 도메인 검증 테스트와 프로토콜 직렬화 테스트를 따로 작성 가능

### F. 정책 판정 context 명시화

현재 `A2aSupervisorRoutingProperties` 자체는 구조가 괜찮지만, 실제 판정 입력은 여러 레이어에서 primitive와 `Map`으로 흩어진다.

신규 context 제안:

- `HitlPolicyContext`
- `InvocationPolicyContext`
- `HandoffPolicyContext`
- `A2uiPolicyContext`

원칙:

- 정책 서비스는 primitive 나열보다 context object를 입력으로 받는다.
- 설정값 읽기와 정책 판정을 분리한다.
- 테스트는 "입력 context -> 판정 결과" 중심으로 단순화한다.

### G. 예외 정규화와 실패 응답 추상화

현재 예외 처리는 orchestrator, compose, graph fallback 경로에 분산되어 있고, 같은 의미의 실패가 서로 다른 문자열과 task 상태 반영 방식으로 표현될 수 있다.

신규 추상화 제안:

- `SupervisorExceptionTranslator`
- `SupervisorFailurePolicy`
- `SupervisorFailureResult`

역할:

- 내부 예외를 도메인 실패 코드로 정규화
- 사용자 노출 메시지와 운영 로그 메시지를 분리
- `task failed`, `progress error`, `fallback message`를 한 정책에서 일관되게 생성

SOLID 관점:

- `S`: 예외 해석과 상태 반영을 orchestration 본문에서 분리
- `O`: 신규 실패 유형 추가 시 조건문 덩어리 대신 translator 확장으로 대응
- `D`: orchestrator가 구체 예외 문자열 처리 대신 failure policy 포트에 의존

### H. 체크포인트/세션 상태 접근 추상화

현재 실행 재개와 graph 시작 준비는 session history, checkpoint, swarm state 접근을 오케스트레이터가 직접 조합한다.

신규 추상화 제안:

- `SupervisorExecutionStateLoader`
- `SupervisorCheckpointPolicy`
- `SupervisorExecutionState`

역할:

- history, checkpointId, swarm state를 한 번에 로드
- checkpoint 사용 가능 여부와 복원 정책 결정
- graph 입력을 위한 준비 상태를 typed object로 제공

효과:

- orchestrator가 persistence storage 세부 규칙을 몰라도 됨
- checkpoint invalidation 정책을 독립적으로 테스트 가능
- 향후 Redis/InMemory 혼합 전략 도입 시 교체 비용 감소

### I. 실행 배치 정책과 실행기 분리

현재 graph 내부 batch 실행 규칙과 orchestrator fallback invoke 규칙은 사실상 같은 정책군인데, 구현 위치가 나뉘어 있다.

신규 추상화 제안:

- `SupervisorBatchExecutionPolicy`
- `SupervisorPlanRunner`
- `SupervisorExecutionBatch`

역할:

- 최대 반복 횟수
- 동시 실행 개수
- 현재 index 기준 실행 batch 선택
- graph 경로와 fallback 경로의 실행 정책 통일

SOLID 관점:

- `S`: graph factory는 노드 정의만, 실행 전략은 별도 정책이 담당
- `L`: 순차/병렬 실행 정책을 구현체 교체로 검증 가능
- `D`: graph가 `CompletableFuture` 세부 구현에 덜 묶임

### J. 결과 선택과 응답 조합 전략 분리

현재 compose와 A2UI는 "어떤 downstream 결과를 사용할지"와 "그 결과로 어떻게 응답을 만들지"가 같은 흐름에 섞여 있다.

신규 추상화 제안:

- `DownstreamResultSelectionPolicy`
- `ComposeCandidate`
- `A2uiCandidate`

역할:

- 성공/실패/혼합 결과 중 compose에 사용할 핵심 후보 선택
- product 결과가 여러 건일 때 우선순위 기준 분리
- A2UI 생성 가능 후보와 일반 텍스트 compose 후보를 분리

효과:

- multi-agent 결과가 늘어나도 compose 코드가 비대해지지 않음
- A2UI provider가 product 외 도메인으로 확장될 때 선택 규칙을 재사용 가능

### K. 직렬화 경계와 protocol adapter 분리

현재 graph state, A2UI payload, progress line, SSE 문자열이 여러 계층에서 직접 문자열/Map으로 만들어진다.

신규 추상화 제안:

- `SupervisorStateSerializer`
- `SupervisorOutputEventSerializer`
- `A2uiProtocolAdapter`

원칙:

- domain/service 계층은 가능한 한 typed object를 유지
- 문자열 직렬화는 adapter 계층 마지막 단계에서만 수행
- wire format 변경이 비즈니스 흐름에 전파되지 않게 한다.

추상화 관점 효과:

- graph checkpoint 포맷 변경 영향 축소
- SSE 출력 포맷 개편 시 orchestrator 수정 최소화
- A2UI protocol 버전 업 대응 용이

### L. Prompt 보호와 템플릿 렌더링 경계 명시화

현재 planning, HITL, compose에서 `PromptInjectionGuard`와 prompt render가 유사한 방식으로 반복 사용된다.

신규 추상화 제안:

- `SupervisorPromptContextFactory`
- `PromptProtectionService`
- `PromptTemplateFacade`

역할:

- user input/history/tool result 보호 규칙 공통화
- 템플릿 입력 변수 생성 규격 통일
- prompt 누락/설정 오류를 한 곳에서 검증

효과:

- planner/compose/HITL 간 prompt 생성 방식 일관화
- 보안 정책 변경 시 여러 서비스 동시 수정 방지
- prompt 단위 테스트 작성 용이

### M. 도메인 서비스 선택 registry 명시화

현재 A2UI provider registry는 있지만, 결과 해석기, extractor, message builder, template selector는 도메인별 확장 계약이 충분히 일반화되어 있지 않다.

신규 추상화 제안:

- `SupervisorDomainCapabilityRegistry`
- `DomainStructuredDataExtractor`
- `DomainResponseAssembler`
- `DomainTemplateSelector`

역할:

- product/search/reservation 등 도메인별 확장 포인트를 공통 계약으로 노출
- if/else 기반 agentKey 분기를 registry 기반으로 대체

SOLID 관점:

- `O`: 신규 도메인 추가 시 기존 compose/A2UI 핵심 흐름 수정 최소화
- `I`: extractor/assembler/selector를 작은 인터페이스로 분리
- `D`: 상위 orchestration이 특정 도메인 구현체를 직접 알지 않음

### N. SOLID 적용 원칙 명시

본 리팩토링은 단순 분해가 아니라 아래 원칙을 만족해야 한다.

- 단일 책임 원칙
  - 클래스는 "한 가지 이유"로만 변경되어야 한다.
  - 예: task 상태 전이는 `SupervisorTaskFacade`, progress/event 기록은 `SupervisorProgressPublisher`, prompt 조립은 `ComposePromptBuilder`
- 개방 폐쇄 원칙
  - 신규 handoff 정책, A2UI 도메인, 실패 정책 추가 시 기존 orchestrator 본문 수정이 최소화되어야 한다.
- 리스코프 치환 원칙
  - 정책/registry/adapter는 테스트 더블 또는 대체 구현체로 치환 가능해야 한다.
- 인터페이스 분리 원칙
  - "큰 범용 서비스"보다 목적별 작은 포트를 우선한다.
  - 예: `ProgressPublisher`, `FailurePolicy`, `PlanRunner`, `StructuredDataExtractor`
- 의존 역전 원칙
  - 상위 application service는 storage, serializer, provider 구현체가 아니라 포트에 의존해야 한다.

---

## 5) 변경 대상 파일

### A. 1차 핵심 변경

- `src/main/java/com/example/springsupervisorai/service/SupervisorAgentService.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorAgentOrchestrator.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorProgressSupport.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorGraphState.java`
- `src/main/java/com/example/springsupervisorai/service/agent/graph/LangGraphSupervisorStateGraphFactory.java`
- `src/main/java/com/example/springsupervisorai/service/agent/compose/LlmSupervisorResponseComposeService.java`
- `src/main/java/com/example/springsupervisorai/service/agent/a2ui/product/DefaultSupervisorProductInfoA2uiService.java`
- `src/main/java/com/example/springsupervisorai/config/A2aSupervisorRoutingProperties.java`

### B. 신규 추가 예상 파일

- `src/main/java/com/example/springsupervisorai/service/HitlGateService.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorExecutionService.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorTaskFacade.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorProgressPublisher.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorGraphExecutionService.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorExecutionPersistenceService.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorFallbackInvokeService.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorExceptionTranslator.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorFailurePolicy.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorExecutionStateLoader.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorCheckpointPolicy.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorGraphSnapshot.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorOutputEvent.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorFailureResult.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorExecutionState.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorExecutionBatch.java`
- `src/main/java/com/example/springsupervisorai/service/agent/graph/SupervisorGraphStateMapper.java`
- `src/main/java/com/example/springsupervisorai/service/agent/graph/SupervisorBatchExecutionPolicy.java`
- `src/main/java/com/example/springsupervisorai/service/agent/graph/SupervisorPlanRunner.java`
- `src/main/java/com/example/springsupervisorai/service/agent/compose/ComposePromptBuilder.java`
- `src/main/java/com/example/springsupervisorai/service/agent/compose/ComposeOutcomeAnalyzer.java`
- `src/main/java/com/example/springsupervisorai/service/agent/compose/A2uiDecisionParser.java`
- `src/main/java/com/example/springsupervisorai/service/agent/compose/DownstreamResultSelectionPolicy.java`
- `src/main/java/com/example/springsupervisorai/service/prompt/SupervisorPromptContextFactory.java`
- `src/main/java/com/example/springsupervisorai/service/prompt/PromptTemplateFacade.java`
- `src/main/java/com/example/springsupervisorai/service/agent/a2ui/product/ProductPayloadExtractor.java`
- `src/main/java/com/example/springsupervisorai/service/agent/a2ui/product/ProductA2uiDataMapper.java`
- `src/main/java/com/example/springsupervisorai/service/agent/a2ui/product/ProductA2uiMessageBuilder.java`
- `src/main/java/com/example/springsupervisorai/service/agent/a2ui/product/ProductPresentationModel.java`
- `src/main/java/com/example/springsupervisorai/service/agent/a2ui/A2uiProtocolAdapter.java`
- `src/main/java/com/example/springsupervisorai/service/agent/domain/SupervisorDomainCapabilityRegistry.java`
- `src/main/java/com/example/springsupervisorai/service/agent/domain/DomainStructuredDataExtractor.java`
- `src/main/java/com/example/springsupervisorai/service/agent/domain/DomainResponseAssembler.java`
- `src/main/java/com/example/springsupervisorai/service/agent/domain/DomainTemplateSelector.java`
- `src/main/java/com/example/springsupervisorai/model/HitlPolicyContext.java`
- `src/main/java/com/example/springsupervisorai/model/HandoffPolicyContext.java`
- `src/main/java/com/example/springsupervisorai/model/InvocationPolicyContext.java`

---

## 6) 우선순위별 실행 계획

### Phase 1 (P0): Service 책임 분리

목표:

- `SupervisorAgentService`를 API use case orchestration만 담당하도록 축소

작업:

- `HitlGateService` 도입
- `SupervisorExecutionService` 도입
- `SupervisorTaskFacade` 도입
- `send/stream/review resume` 공통 실행 계약 정의

완료 기준:

- `SupervisorAgentService`에서 직접 task 상태 전이를 최소화
- `collectOrchestrationPayload(...)`와 review resume 세부 구현이 별도 서비스로 이동
- 기존 API 계약 유지

### Phase 2 (P0): Orchestrator 부수효과 분리

목표:

- orchestrator에서 progress/persistence/task completion 책임 축소

작업:

- `SupervisorProgressPublisher` 도입
- `SupervisorExecutionPersistenceService` 도입
- `SupervisorFallbackInvokeService` 도입

완료 기준:

- orchestrator가 progress 문자열을 직접 조립하지 않음
- compose 완료 후 persistence/task update 코드가 별도 서비스에 존재
- swarm event log가 publisher 경유로만 기록됨

### Phase 3 (P1): Graph typed state + mapper 분리

목표:

- graph 상태 변환 코드를 factory 바깥으로 이동

작업:

- `SupervisorGraphSnapshot` 도입
- `SupervisorGraphStateMapper` 도입
- `SupervisorGraphState.toPlanningContext()` 의존 축소

완료 기준:

- `LangGraphSupervisorStateGraphFactory` 내부 `toPlanMap`, `toResultList`, `readResults`류 코드 제거 또는 대폭 축소
- graph node의 평균 길이 감소

### Phase 4 (P1): typed output event 도입

목표:

- progress/text/a2ui/error를 구분 가능한 내부 출력 모델로 승격

작업:

- `SupervisorOutputEvent` 정의
- orchestrator/compose/service 내부는 typed event 사용
- 최종 SSE adapter에서만 문자열 변환

반영 메모 (2026-04-17):

- `SupervisorOutputEvent`, `SupervisorOutputEventType`, `SupervisorOutputEventSupport` 추가
- `SupervisorResponseComposeService#streamComposeEvents(...)` 추가
- `SupervisorAgentOrchestrator#executeEvents(...)` 추가
- `SupervisorExecutionService#executeStreamEvents(...)` 추가
- 외부 `Flux<String>` 계약은 adapter를 통해 유지

완료 기준:

- `SupervisorA2uiSupport.isWrapped(...)`에 의존하는 후처리 축소
- answer 누적 시 A2UI payload를 문자열 패턴으로 구분하지 않음

반영 메모 (2026-04-17 추가):

- `SupervisorProgressPublisher`에 `recordProgress(...)`, `recordEvent(...)` 추가
- `LangGraphSupervisorStateGraphFactory`는 graph node event 기록 시 publisher만 사용
- `SupervisorGraphExecutionService`도 graph 시작 이벤트를 publisher 경로로 기록
- `SupervisorAgentOrchestrator`도 progress 발행 시 `taskId/sessionId/nodeType` 문맥을 함께 전달하도록 정리
- `LangGraphSupervisorStateGraphFactory`의 invoke node 배치 선택/실행은 `SupervisorBatchExecutionPolicy`, `SupervisorPlanRunner`로 분리
- `SupervisorFallbackInvokeService`도 같은 `SupervisorBatchExecutionPolicy`, `SupervisorPlanRunner`를 사용하도록 정리
- `HitlPolicyContext`, `HandoffPolicyContext` 도입으로 정책 입력을 primitive 중심에서 typed model 중심으로 전환
- `SupervisorExecutionStateLoader`, `SupervisorExceptionTranslator` 도입으로 상태 접근과 실패 정규화를 orchestration 본문에서 분리
- `InvocationPolicyContext` 도입으로 policy 입력 typed context 전환 마무리

### Phase 5 (P1): Compose 분해

목표:

- compose 의사결정, prompt 조립, A2UI decision parsing을 분리

작업:

- `ComposeOutcomeAnalyzer`
- `ComposePromptBuilder`
- `A2uiDecisionParser`
- `ComposeFallbackMessageBuilder`

완료 기준:

- `LlmSupervisorResponseComposeService`는 orchestration facade 수준으로 축소
- outcome 판정 테스트와 prompt 테스트를 별도 작성 가능

### Phase 6 (P2): Product A2UI 분해

목표:

- A2UI payload 생성 로직을 domain extraction / presentation mapping / protocol assembly로 분리

작업:

- `ProductPayloadExtractor`
- `ProductA2uiDataMapper`
- `ProductPresentationModel`
- `ProductA2uiMessageBuilder`

완료 기준:

- `DefaultSupervisorProductInfoA2uiService`는 facade 또는 조합 서비스 수준으로 축소
- JSON 탐색과 component tree 생성이 분리됨

### Phase 7 (P2): 정책 context 정리

목표:

- 정책 서비스 입력을 명시적 context로 통일

작업:

- `HitlPolicyContext`
- `InvocationPolicyContext`
- `HandoffPolicyContext`
- `A2uiPolicyContext`

완료 기준:

- 정책 테스트가 primitive/Map mocking 없이 작성 가능
- 설정 읽기와 정책 판정 로직이 분리됨

### Phase 8 (P2): 실패 처리와 checkpoint 접근 추상화

목표:

- 예외 처리와 실행 상태 로드를 별도 추상화로 정리

작업:

- `SupervisorExceptionTranslator`
- `SupervisorFailurePolicy`
- `SupervisorExecutionStateLoader`
- `SupervisorCheckpointPolicy`

완료 기준:

- orchestrator/compose에서 예외 문자열 직접 분기 축소
- history/checkpoint/swarm 접근이 단일 loader를 통해 이뤄짐

### Phase 9 (P2): 실행 정책/결과 선택/직렬화 adapter 정리

목표:

- graph/fallback 실행 규칙, compose 후보 선택, 최종 직렬화 경계를 분리

작업:

- `SupervisorBatchExecutionPolicy`
- `SupervisorPlanRunner`
- `DownstreamResultSelectionPolicy`
- `SupervisorOutputEventSerializer`
- `A2uiProtocolAdapter`

완료 기준:

- graph와 fallback이 같은 배치 정책을 공유
- 결과 선택 기준이 compose/A2UI 본문에서 분리
- 문자열 직렬화가 adapter 계층 마지막 단계로 이동

### Phase 10 (P3): 도메인 capability registry 일반화

목표:

- product 외 도메인 확장을 위한 registry 구조 정비

작업:

- `SupervisorDomainCapabilityRegistry`
- `DomainStructuredDataExtractor`
- `DomainResponseAssembler`
- `DomainTemplateSelector`

완료 기준:

- agentKey 분기 하드코딩 축소
- search/reservation 도메인 추가 시 기존 compose/A2UI 본문 변경 최소화

---

## 7) 추천 구현 순서

권장 순서는 아래와 같다.

1. `SupervisorAgentService` 분리
2. `SupervisorAgentOrchestrator`의 progress/persistence 분리
3. output event typed model 도입
4. `LangGraphSupervisorStateGraphFactory`의 mapper/typed state 분리
5. `LlmSupervisorResponseComposeService` 분리
6. `DefaultSupervisorProductInfoA2uiService` 분리
7. 정책 context 정리
8. 실패 처리와 checkpoint/state loader 추상화
9. 실행 정책/결과 선택/직렬화 adapter 정리
10. 도메인 capability registry 일반화

이 순서를 추천하는 이유:

- 먼저 request flow 경계를 정리해야 이후 graph/compose 리팩토링이 상위 흐름을 덜 흔든다.
- progress/event 단일화와 typed output이 먼저 들어가야 A2UI 및 compose 후처리 리팩토링이 쉬워진다.
- graph typed state는 영향 범위가 커서 service/orchestrator 경계 정리 후 진행하는 편이 안전하다.
- 실패 정책과 state loader는 흐름 안정화 이후 넣어야 추상화가 실제 경계를 반영한다.
- 도메인 capability registry는 product 중심 구조가 정리된 뒤 확장하는 편이 과설계를 피할 수 있다.

---

## 8) 테스트 전략

### A. 단위 테스트

대상:

- `HitlGateService`
- `SupervisorExecutionService`
- `SupervisorTaskFacade`
- `SupervisorProgressPublisher`
- `SupervisorGraphStateMapper`
- `ComposeOutcomeAnalyzer`
- `A2uiDecisionParser`
- `ProductPayloadExtractor`
- `ProductA2uiDataMapper`
- `SupervisorExceptionTranslator`
- `SupervisorExecutionStateLoader`
- `SupervisorBatchExecutionPolicy`
- `DownstreamResultSelectionPolicy`
- `SupervisorOutputEventSupport`

핵심 검증:

- send/stream/resume 분기 일관성
- review approve/cancel 상태 전이
- progress publish 시 user-facing event와 swarm log 동시 기록
- graph snapshot <-> state 변환 정합성
- sync/resume payload 수집 시 progress/a2ui wrapper가 task payload에 섞이지 않는지 검증
- compose A2UI parse/repair fallback
- product payload extraction 성공/실패 경로
- 동일 예외 입력에 대해 task 상태/사용자 메시지/로그 코드가 일관되게 생성되는지 검증
- checkpoint 복원 가능/불가 조건이 loader/policy에서 일관되게 판단되는지 검증
- 순차/병렬/fallback 실행 정책이 동일한 batch 선택 규칙을 따르는지 검증
- output event -> legacy string adapter 변환 규칙이 타입별로 일관적인지 검증

### B. 통합 테스트

대상 시나리오:

1. send 요청
- HITL 불필요
- orchestration 완료
- task completed 반영

2. stream 요청
- progress -> compose -> done 순서 유지
- cancel 시 task canceled 반영

3. review approve
- waiting review -> running -> completed 전이

4. review cancel
- waiting review -> canceled 전이

5. graph + fallback
- graph 결과 없음
- fallback invoke 수행
- compose 완료

6. A2UI compose
- text + a2ui payload 동시 생성
- A2UI 실패 시 text fallback 유지

### C. 회귀 테스트

- 기존 `SupervisorAgentServiceTest`
- 기존 `SupervisorAgentOrchestratorExecuteTest`
- 기존 `SupervisorAgentOrchestratorCheckpointTest`
- 기존 `LlmSupervisorResponseComposeServiceTest`

전략:

- 기존 테스트는 계약 보존 여부를 검증하는 회귀 테스트로 유지
- 신규 서비스 단위 테스트를 추가해 대형 클래스 테스트 의존도를 줄인다.

---

## 9) 2026-04-17 후속 개선 백로그

38 P0/P1 리팩토링으로 상위 책임 분리, typed event 도입, graph 실행 정책 분리, compose/A2UI 분해의 1차 목표는 달성되었다.

다만 현재 구현에는 "경계는 정리됐지만 내부 정합성을 더 높여야 하는 지점"이 남아 있으며, 아래 항목을 후속 개선 백로그로 관리한다.

### A. sync/resume payload 수집 경계 정리

현재 `SupervisorExecutionService`는 `executeEvents(...)` 결과를 legacy 문자열로 직렬화한 뒤 모두 이어붙여 task payload를 만든다.

남은 문제:

- progress line, text, A2UI wrapper가 같은 문자열 채널로 합쳐질 수 있음
- sync/send 결과 payload와 stream 출력 계약이 불필요하게 결합됨
- 향후 output event type 추가 시 task persistence 경계가 다시 오염될 수 있음

개선 방향:

- `ExecutionResultCollector` 또는 동등한 collector를 도입해 `TEXT`, `A2UI`, `ERROR`를 구조적으로 수집
- `SupervisorExecutionService#executeSync(...)`, `resumeApprovedTask(...)`는 collector 결과만 persistence에 반영
- progress event는 user-facing stream과 operational log에만 남기고 task payload에서는 제외

완료 기준:

- sync/resume 완료 payload에 progress line이 저장되지 않음
- A2UI payload 저장 여부가 명시적 정책으로 드러남
- legacy `serialize(...)`는 stream adapter 전용 경계로 축소됨

### B. `SupervisorAgentService` 잔여 책임 2차 축소

현재 `SupervisorAgentService`는 진입점 역할로 많이 축소됐지만, 아래 책임이 여전히 혼재한다.

- review decision 분기
- approve/cancel 후속 실행 연결
- stream 시작 전 HITL preface/progress 문구 조립

개선 방향:

- `HitlReviewApplicationService` 또는 `SupervisorReviewUseCase`를 도입해 review decision 흐름을 분리
- stream preface/HITL 안내 메시지는 별도 response assembler 또는 progress policy로 이동
- `SupervisorAgentService`는 request validation 이후 use case dispatch에 집중

완료 기준:

- `SupervisorAgentService`는 send/stream/review 진입 위임과 response mapping 중심으로 축소
- review approve/cancel 상태 전이 세부 분기가 별도 서비스로 이동
- stream 초기 progress 문구 변경이 use case service 변경 없이 가능

### C. graph typed state 전환 마무리

`SupervisorGraphSnapshot`, `SupervisorGraphStateMapper`는 도입됐지만, 일부 경계에는 아직 raw `Map`과 문자열 key 접근이 남아 있다.

남은 지점:

- `SupervisorGraphExecutionService`의 graph input 조립
- `SupervisorAgentOrchestrator`의 handoff metadata 계산
- 일부 graph/orchestrator 경계의 `SupervisorGraphState` 직접 조회

개선 방향:

- `SupervisorGraphInputBuilder` 또는 `SupervisorGraphSnapshotFactory`를 도입해 graph input 조립을 typed object 기반으로 이동
- handoff progress metadata는 `HandoffProgressMetadataFactory` 같은 helper로 분리
- state 읽기 로직은 mapper/snapshot 경유 접근으로 통일

완료 기준:

- service/orchestrator 계층에서 `SupervisorGraphState.*` key 직접 참조를 최소화
- graph input 생성과 state 조회가 typed helper를 통해 일관되게 수행
- handoff metadata 계산 로직이 raw map parsing 없이 테스트 가능

### D. typed output event를 controller 경계까지 승격

내부 compose/orchestrator/execution 경계는 상당 부분 `SupervisorOutputEvent` 기반으로 정리됐지만, 서비스와 컨트롤러 외곽은 여전히 `Flux<String>` 중심이다.

개선 방향:

- service 기본 계약은 `Flux<SupervisorOutputEvent>`를 우선으로 두고, `Flux<String>`는 adapter 계약으로만 유지
- controller에서 SSE event name과 payload 매핑을 담당
- `SupervisorOutputEventSupport`는 legacy wire adapter 역할로 한정

완료 기준:

- 문자열 직렬화는 controller 또는 명시적 adapter 계층 마지막 단계에서만 발생
- service 계층 테스트는 문자열 파싱 없이 event type 기준으로 작성 가능
- A2UI/event/error SSE 매핑 규칙이 controller 경계에서 명확히 드러남

### E. `SupervisorAgentOrchestrator` 2차 슬림화

`SupervisorAgentOrchestrator`는 핵심 책임 분리는 됐지만 여전히 크기가 크고, 요약/로그/metadata 계산 유틸이 내부에 많이 남아 있다.

우선 후보:

- routing summary progress emit
- graph result summary logging
- handoff progress metadata 계산
- progress message formatting helper

개선 방향:

- `SupervisorExecutionSummaryEmitter`
- `SupervisorRoutingProgressFormatter`
- `SupervisorHandoffProgressSupport`

같은 소형 helper/service로 나눠 coordinator 본문을 줄인다.

완료 기준:

- 오케스트레이터는 pipeline coordination, cancellation, error boundary에 집중
- summary/log/progress 보조 계산이 별도 협력 객체로 이동
- 주요 메서드 길이와 private helper 수가 눈에 띄게 감소

### F. 새로 분리된 컴포넌트 전용 계약 테스트 보강

현재 상위 회귀 테스트는 유지되고 있으나, 새로 도입한 분리 컴포넌트에 대한 전용 테스트는 추가 보강이 필요하다.

우선 보강 대상:

- `SupervisorGraphStateMapper` round-trip 테스트
- `SupervisorExecutionStateLoader` invalid checkpoint 정리 테스트
- `SupervisorProgressPublisher`의 user-facing progress + swarm log 동시 기록 테스트
- `SupervisorOutputEventSupport`의 type별 serialization 테스트
- sync/resume payload collector 테스트

완료 기준:

- 상위 통합 테스트 실패 없이 하위 계약 테스트만으로 경계 회귀를 조기에 검출 가능
- 문자열 기반 대형 회귀 테스트 의존도가 점진적으로 감소

### G. 다음 기능 backlog 사전 설계

문서 17과 현재 구현 메모 기준으로 아래 항목은 아직 다음 단계 기능 backlog로 남아 있다.

- `REVISE` review decision
- 사용자 추가정보 수집 UX

개선 방향:

- 지금 단계에서는 구현보다도 typed request/result 모델과 상태 전이 지점을 먼저 설계 메모로 남긴다.
- review decision 확장 시 현재 approve/cancel 흐름에 어떤 application service를 추가할지 명시한다.

완료 기준:

- 다음 기능 추가 시 현재 리팩토링 경계를 다시 무너뜨리지 않도록 선행 설계 문서가 준비됨

---

## 10) 후속 개선 실행 계획

후속 개선은 아래 순서로 진행하는 것을 권장한다.

### Phase 11 (P0): sync/resume payload collector 도입

목표:

- task payload persistence와 stream 문자열 계약을 분리

작업:

- `ExecutionResultCollector` 또는 동등한 collector 도입
- `SupervisorExecutionService`의 `collectPayload(...)` 제거 또는 collector 기반으로 대체
- sync/resume payload 저장 정책 명시

완료 기준:

- progress line이 task payload에 저장되지 않음
- send/resume 결과 저장이 event type 기준으로 동작

### Phase 12 (P0): `SupervisorAgentService` 2차 경량화

목표:

- review decision과 stream preface 책임을 상위 진입점에서 분리

작업:

- `HitlReviewApplicationService` 또는 `SupervisorReviewUseCase` 도입
- stream 초기 progress 조립 helper 분리
- `SupervisorAgentService`는 use case dispatch 중심으로 축소

완료 기준:

- review approve/cancel 분기가 별도 서비스로 이동
- `SupervisorAgentService`의 private branch/helper 수 감소

### Phase 13 (P1): graph typed 경계 마무리

목표:

- graph input/state metadata 조립에서 raw key 접근 제거

작업:

- `SupervisorGraphInputBuilder` 도입
- handoff metadata helper 도입
- orchestrator/service의 raw state 조회를 snapshot 기반 접근으로 전환

완료 기준:

- service/orchestrator 계층의 `SupervisorGraphState.*` 직접 참조 최소화
- handoff metadata 계산에 대한 독립 테스트 확보

### Phase 14 (P1): output event 외부 경계 정리

목표:

- typed output event를 controller 경계까지 기본 계약으로 승격

작업:

- service 기본 반환형을 event 중심으로 정리
- controller에서 SSE event type 매핑 수행
- legacy string adapter 책임 축소

완료 기준:

- service 테스트가 문자열 비교 대신 event assertion 중심으로 작성
- controller가 wire format adapter 역할을 명시적으로 담당

### Phase 15 (P1): orchestrator 2차 슬림화 + 테스트 보강

목표:

- coordinator를 더 얇게 만들고 새 경계에 대한 계약 테스트를 강화

작업:

- summary/log/progress helper 분리
- `SupervisorGraphStateMapper`, `SupervisorExecutionStateLoader`, `SupervisorProgressPublisher`, `SupervisorOutputEventSupport` 전용 테스트 추가
- 상위 회귀 테스트와 하위 계약 테스트 역할 분담 정리

완료 기준:

- 오케스트레이터 크기와 보조 유틸 응집도 개선
- 새 분리 경계에 대한 회귀가 단위 테스트에서 먼저 검출됨

### Phase 16 (P2): 다음 기능 backlog 설계 메모 정리

목표:

- `REVISE`, 추가정보 수집 기능이 현재 경계 위에서 확장 가능하도록 준비

작업:

- decision/state transition 설계 메모 추가
- 필요한 typed request/result 모델 초안 정리

완료 기준:

- 다음 기능 추가 시 리팩토링 구조를 다시 해치지 않는 설계 기준 확보

### Phase 16 상세 설계 메모

#### A. `REVISE` review decision

정의:

- `REVISE`는 reviewer가 단순 승인/취소가 아니라 실행 전제나 실행 파라미터를 수정해 재실행을 요청하는 결정이다.

설계 원칙:

- `APPROVE/CANCEL` 경로에 조건문만 추가하지 않는다.
- `SupervisorReviewApplicationService` 아래에 `SupervisorReviewRevisionService`를 별도 도입해 수정 흐름을 분리한다.
- 수정 입력은 primitive 조합 대신 typed amendment 모델로 표현한다.

예상 typed model:

- `HitlRevisionRequest`
- `HitlRevisionPatch`
- `SupervisorExecutionAmendment`
- `RevisionFieldChange`

예상 상태 전이:

1. `WAITING_REVIEW`
2. `REVISED`
3. `RUNNING`
4. `COMPLETED` 또는 `FAILED`

권장 contract 초안:

- `SupervisorReviewApplicationService#reviseReview(sessionId, taskId, revisionRequest, decisionId)`
- `SupervisorReviewRevisionService#applyRevision(ticket, revisionRequest): SupervisorExecutionRequest`

주의점:

- 원본 user message와 reviewer amendment를 분리 저장해 감사 추적이 가능해야 한다.
- task payload 최종 응답과 revision metadata는 별도 필드/별도 audit log로 관리하는 편이 안전하다.
- `REVISE`는 review store, task lifecycle, execution request 생성 규칙이 동시에 바뀌므로 P2에서만 처리한다.

#### B. 사용자 추가정보 수집

정의:

- 추가정보 수집은 review decision의 변형이 아니라, 실행 전에 필요한 필수 슬롯이 비어 있을 때 사용자에게 정보를 요청하고 다시 execution gating으로 복귀하는 흐름이다.

설계 원칙:

- `SupervisorExecutionService` 안에서 직접 질문/응답 루프를 돌리지 않는다.
- `MissingInfoCollectionService` 또는 `ExecutionPreconditionService`를 두고 execution 진입 전에 전제조건을 충족시킨다.
- transport 계층은 자연어/콤마 텍스트를 수용하되 내부는 structured field map으로 정규화한다.

예상 typed model:

- `MissingInfoRequest`
- `MissingInfoField`
- `MissingInfoAnswer`
- `CollectedUserProfile`

예상 상태 전이:

1. `RUNNING`
2. `WAITING_FOR_USER_INPUT`
3. `RUNNING`
4. `COMPLETED` 또는 `FAILED`

권장 contract 초안:

- `ExecutionPreconditionService#evaluate(request): PreconditionResult`
- `MissingInfoCollectionService#openPrompt(taskId, fields): MissingInfoRequest`
- `MissingInfoCollectionService#applyAnswer(taskId, answer): SupervisorExecutionRequest`

주의점:

- review approval과 달리 추가정보 수집은 end-user 입력 루프이므로, reviewer 권한 모델과 혼합하지 않는다.
- A2UI가 있다면 입력 form surface로 재사용할 수 있지만, 내부 domain contract는 A2UI 여부와 분리한다.
- `SupervisorOutputEventType`를 바로 늘리기보다 task/review payload 또는 A2UI payload로 우선 표현하는 편이 외부 계약 안정성에 유리하다.

#### C. 문서/구현 동기화 원칙

- `REVISE`를 실제 구현할 때는 `17`, `38`, `02-package-policy.puml`, 상태 머신 문서(`11`)를 동시에 갱신한다.
- 추가정보 수집을 구현할 때는 controller contract, task state machine, A2UI interaction 문서를 함께 갱신한다.
- 두 기능 모두 "기존 approve/cancel 경계에 조건문 추가" 방식으로 구현하지 않고, application service와 typed model을 추가하는 방향을 유지한다.

---

## 11) 리스크와 대응 전략

### A. 리스크: 리팩토링 중 외부 계약 훼손

대응:

- public API와 SSE 출력 계약은 단계별로 유지
- typed output event는 내부 전환 후 adapter 방식으로 외부 문자열 유지

### B. 리스크: graph 상태 전환 중 checkpoint 호환성 문제

대응:

- 1차는 기존 key를 유지한 mapper 기반 typed wrapper 방식으로 진행
- checkpoint 포맷 마이그레이션은 별도 단계로 분리

### C. 리스크: progress/event 통합 시 로그 중복

대응:

- publisher를 단일 진입점으로 강제
- 기존 직접 `recordNodeEvent(...)` 호출은 단계적으로 제거

### D. 리스크: 클래스 수 증가로 구조가 과하게 분산

대응:

- facade + policy + mapper + builder 수준까지만 분리
- 1개 클래스가 단일 이유로만 변경되도록 유지
- 패키지 구조를 `service/graph/compose/a2ui/policy` 축으로 정리

---

## 12) 문서 결론

현재 코드베이스는 새로운 기능을 더 넣는 것보다 Supervisor 내부의 책임 경계를 먼저 재정렬하는 것이 맞다.

핵심 포인트는 아래 4가지다.

- `SupervisorAgentService`는 use case orchestration만 담당해야 한다.
- `SupervisorAgentOrchestrator`는 pipeline coordinator로 축소되어야 한다.
- graph 상태와 출력 스트림은 typed object 중심으로 전환되어야 한다.
- compose/A2UI는 decision, extraction, mapping, assembly로 분해되어야 한다.

이 계획대로 진행하면 다음 효과를 기대할 수 있다.

- send/stream/review resume 흐름 가독성 향상
- 테스트 단위 축소 및 회귀 범위 명확화
- handoff/HITL/A2UI 확장 시 변경 영향 축소
- 운영 trace와 사용자 progress의 일관성 확보

추가 운영 원칙:

- 리팩토링 작업 완료 후에는 동일 폴더 `documents/a2a-host_agent-architecture` 아래의 관련 `puml`, `md` 문서도 반드시 함께 검토하고 소스와 동기화한다.
- 특히 클래스 책임, 시퀀스, 상태 머신, 패키지 경계가 바뀌는 경우 구현 코드만 변경하고 문서를 남겨두는 상태를 허용하지 않는다.
- 구현 완료의 정의(Definition of Done)에는 "관련 아키텍처 문서 업데이트"를 포함한다.

본 문서는 이후 실제 구현 시 P0/P1/P2 백로그와 체크리스트의 기준 문서로 사용한다.
