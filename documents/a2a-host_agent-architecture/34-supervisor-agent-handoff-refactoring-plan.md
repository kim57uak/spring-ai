# 34. Supervisor Agent Handoff 적용 리팩토링 기획문서

## 1) 목적/범위

본 문서는 현재 `com.example.springsupervisorai`의 Supervisor 오케스트레이션에 **agent 간 handoff**를 도입하기 위한 리팩토링 계획을 정의한다.

- 목적: 단순 순차 호출(plan list 소비)에서 벗어나, 하위 agent 실행 결과를 기반으로 다음 agent로 제어를 안전하게 이관
- 범위: Supervisor 경계(`model/service/graph/store/invoke`) 및 SwarmState 확장
- 제외: 하위 agent 내부 도메인 로직 변경(상품/예약/검색 서비스 구현 자체)
- 대상 경로: `/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai`

---

## 2) AS-IS 분석 요약

현재 구현은 `PLAN -> SELECT -> INVOKE -> MERGE -> COMPOSE` 그래프를 반복하며 `RoutingPlan` 목록을 소비한다.

- 그래프 구조상 handoff 전용 상태/분기 없음
  - 근거: `service/agent/graph/LangGraphSupervisorStateGraphFactory`
- `RoutingPlan`은 정적 실행 항목(`agentKey/method/reason/priority/arguments`)만 표현
  - 근거: `model/RoutingPlan`
- `DownstreamCallResult`는 호출 결과 중심이며 "다음 agent 이관 지시"를 표준 필드로 다루지 않음
  - 근거: `model/DownstreamCallResult`, `service/agent/invoke/DefaultA2AInvocationService`
- `SwarmState`는 cooldown/circuit/eventLog 중심
  - handoff 이력/loop 제어용 팩트 모델이 없음
  - 근거: `model/SwarmState`, `service/agent/swarm/DefaultSupervisorSwarmCoordinator`

정리하면, 현재 구조는 agentic orchestration은 수행하지만 **handoff를 1급 개념(first-class concept)으로 취급하지는 않는다.**

---

## 3) TO-BE 목표 아키텍처

핵심 목표는 "계획(plan) + 동적 이관(handoff)" 하이브리드 실행이다.

- Planner가 초기 후보 경로를 생성
- Invoke 결과가 handoff 지시를 포함하면 Supervisor가 유효성 검증 후 실행 큐에 동적 삽입
- SwarmState에 handoff 체인/횟수/최근 이관 원인 기록
- Graph는 `HandoffEvaluate -> HandoffApply` 분기를 통해 제어 흐름을 명시적으로 관리

핵심 원칙:

- handoff는 **허용된 agent allowlist** 내부에서만 동작
- 무한 이관 방지를 위해 hop 수/중복 agent/시간 윈도우를 강제
- handoff 실패 시 기존 plan 경로로 안전 폴백
- 리팩토링은 **SOLID, 추상화, 가독성, 유지보수성**을 최우선 기준으로 수행

리팩토링 품질 원칙:

- SOLID
  - `S`: handoff 파싱/검증/적용/기록 책임 분리
  - `O`: 신규 handoff 정책 추가 시 기존 그래프 핵심 로직 수정 최소화
  - `L`: `HandoffPolicyService` 구현체 교체 가능성 보장
  - `I`: 작고 명확한 인터페이스로 분리(Policy, Coordinator, Progress emitter)
  - `D`: Orchestrator/Graph는 구현체가 아닌 포트(인터페이스)에 의존
- 추상화
  - 도메인 의도(`HandoffDirective`, `HandoffValidationResult`)를 원시 Map/문자열보다 우선 사용
  - UI 진행상태 출력은 `SupervisorProgressSupport` 공통 abstraction으로 통일
- 가독성
  - graph node action은 짧고 단일 책임 메서드로 분해
  - 조건 분기는 guard clause 우선으로 중첩 최소화
  - 상수/enum 기반으로 상태/이벤트 문자열 하드코딩 제거
- 유지보수성
  - feature flag(`handoff.enabled`)로 위험 제어
  - Javadoc + 테스트(단위/통합/회귀)로 변경 의도와 회귀 방지 장치 확보
  - 운영 지표(progress/event log)로 장애 분석 가능성 보장

---

## 4) 설계 변경안

### A. 도메인 모델 확장

1. `DownstreamCallResult` 확장
- `handoffRequested: boolean`
- `nextAgentKey: String`
- `handoffMethod: String` (optional, 없으면 기본 send method)
- `handoffReason: String`
- `handoffArguments: Map<String, Object>`

2. `RoutingPlan` 확장
- `sourceType: "PLANNER" | "HANDOFF"`
- `handoffDepth: int`
- `parentAgentKey: String` (handoff 유발 agent)

3. 신규 값 객체
- `HandoffDirective` (정규화된 이관 지시)
- `HandoffValidationResult` (허용/차단 사유)

### B. SwarmState 확장

`sharedFacts`에 아래 키를 표준화한다.

- `handoffHopCount`
- `handoffPath` (예: `["search","product","reservation"]`)
- `handoffBlockedCount`
- `lastHandoffAgent`
- `lastHandoffAt`

`eventLog` 타입 추가:

- `HANDOFF_REQUESTED`
- `HANDOFF_ACCEPTED`
- `HANDOFF_REJECTED`
- `HANDOFF_LIMIT_REACHED`

### C. Graph/Orchestrator 변경

기존 노드 흐름에 handoff 분기를 추가한다.

- 기존: `... -> INVOKE -> MERGE -> SELECT ...`
- 변경: `... -> INVOKE -> HANDOFF_EVALUATE -> HANDOFF_APPLY -> MERGE -> SELECT ...`

노드 책임:

- `HANDOFF_EVALUATE`: `DownstreamCallResult`에서 directive 추출 + 정책 검증
- `HANDOFF_APPLY`: 허용된 directive를 `routingPlans`에 삽입하거나 현재 index 조정

### D. 검증/보안 규칙

1. allowlist 검증
- `A2aSupervisorRoutingProperties.routing`에 존재하는 agentKey만 허용

2. 루프/폭주 방지
- `maxHandoffHops` (기본 3)
- 동일 agent 재방문 제한(최근 N step 중복 금지)
- 세션 단위 `handoffRateLimit` (예: 1분 10회)

3. 메서드 제한
- handoff method도 기존 허용 메서드 enum만 허용
- stream 미지원 agent로의 stream handoff 금지

4. 폴백
- 검증 실패 시 handoff 무시 + 기존 계획 계속
- 실패 사유를 Swarm eventLog에 기록

### E. On/Off Feature Flag (필수)

운영 리스크를 낮추기 위해 handoff는 설정 기반으로 즉시 비활성화 가능해야 한다.

- `handoff.enabled` (boolean, 기본 `false`)
- 적용 지점: `INVOKE` 결과 처리 직후
- 동작 규칙:
  - `enabled=false`: handoff directive 파싱/검증/적용 전체를 건너뛰고 기존 `plan -> merge -> compose` 흐름 유지
  - `enabled=true`: handoff 정책(`maxHops`, `allowMethods`, allowlist, rate-limit) 적용 후 동적 라우팅 수행
- 관측성:
  - 이벤트 로그에 `handoffEnabled=true|false` 기록
  - OFF 상태에서 directive가 와도 `HANDOFF_SKIPPED_BY_FLAG` 이벤트를 남긴다.

### F. 진행상태/생각과정 UI 출력 (공통모듈 사용)

handoff 단계는 UI의 "생각 과정(Trace/Progress)"에 노출되어야 하며, 문자열 하드코딩 대신 기존 공통 모듈을 사용한다.

- 공통 모듈: `SupervisorProgressSupport`, `SupervisorProgressEvent`
- 원칙:
  - `SupervisorAgentOrchestrator`와 `SupervisorAgentService` 모두 동일 포맷(`stage/progress/message/metadata`) 사용
  - 신규 handoff 단계는 `SupervisorProgressSupport` 상수로 정의 후 재사용
  - 임의 포맷 문자열 직접 생성 금지
- 신규 stage 제안:
  - `STAGE_HANDOFF`
  - `STAGE_HANDOFF_SKIPPED`
  - `STAGE_HANDOFF_APPLIED`
- 최소 메타데이터:
  - `handoffEnabled`
  - `fromAgent`
  - `toAgent`
  - `reason`
  - `hopCount`

### G. Javadoc 상세 주석 적용 기준

handoff 리팩토링으로 신규/수정되는 public 타입과 핵심 메서드에는 Javadoc을 필수로 추가한다.

- 대상:
  - public class/interface/record
  - graph node action 메서드
  - handoff 정책 검증 메서드
  - swarm state upsert/merge 메서드
- 필수 항목:
  - 책임(무엇을 하는지)
  - 입력/출력(`@param`, `@return`)
  - 실패/제약(`@throws`, allowlist/hop limit 조건)
  - 부작용(상태 변경, event log 기록)
- 품질 기준:
  - "코드 반복 설명"이 아니라 의사결정 규칙과 운영 제약을 기술
  - 한/영 혼용 가능하나 프로젝트 기존 스타일과 일관성 유지

---

## 5) 변경 대상 파일(우선순위)

1. 필수 수정
- `src/main/java/com/example/springsupervisorai/model/RoutingPlan.java`
- `src/main/java/com/example/springsupervisorai/model/DownstreamCallResult.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorGraphNode.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorGraphRoute.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorPlanningContext.java`
- `src/main/java/com/example/springsupervisorai/model/SupervisorGraphState.java`
- `src/main/java/com/example/springsupervisorai/service/agent/graph/LangGraphSupervisorStateGraphFactory.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorAgentOrchestrator.java`
- `src/main/java/com/example/springsupervisorai/service/agent/swarm/DefaultSupervisorSwarmCoordinator.java`
- `src/main/java/com/example/springsupervisorai/service/SupervisorProgressSupport.java`

2. 신규 추가
- `src/main/java/com/example/springsupervisorai/model/HandoffDirective.java`
- `src/main/java/com/example/springsupervisorai/model/HandoffValidationResult.java`
- `src/main/java/com/example/springsupervisorai/service/agent/handoff/HandoffPolicyService.java`
- `src/main/java/com/example/springsupervisorai/service/agent/handoff/DefaultHandoffPolicyService.java`

3. 설정 확장
- `src/main/java/com/example/springsupervisorai/config/A2aSupervisorRoutingProperties.java`
- `src/main/resources/application.yml` 또는 supervisor 설정 yml

---

## 6) 단계별 실행 계획

### Phase 1 (P0): 모델/설정 기반 구축

- `DownstreamCallResult`, `RoutingPlan` 확장
- `HandoffDirective`/`HandoffValidationResult` 추가
- `A2aSupervisorRoutingProperties`에 `handoff` 정책 블록 추가
- `handoff.enabled` feature flag 추가(기본 OFF)

### Phase 2 (P1): 그래프 분기 도입

- `HANDOFF_EVALUATE`, `HANDOFF_APPLY` 노드 추가
- `invoke -> handoff -> merge` 경로 연결
- 노드별 progress/event 출력 정비
- handoff 단계 진행상태를 `SupervisorProgressSupport` 공통 포맷으로 emit

### Phase 3 (P1): SwarmState 통합

- handoff 팩트/이벤트 반영
- session 최신 상태 기반 hop count 복원
- 충돌/재시도 시 상태 정합성 검증

### Phase 4 (P2): 안정화/회귀

- fallback 경로 검증
- loop 방지/레이트리밋 테스트
- 기존 non-handoff 요청 성능/동작 회귀 점검
- ON/OFF 토글 전환 시 동작 일관성 검증(재기동 없이 반영 여부 포함)
- UI trace 패널에서 handoff 단계 메시지 표시 검증

---

## 7) 테스트 전략

1. 단위 테스트
- handoff directive 파싱/정규화
- allowlist/method/stream 지원 여부 검증
- hop count/중복 agent 차단 로직 검증

2. 통합 테스트
- 단일 handoff 성공: `agentA -> agentB` 이관 후 정상 compose
- 다중 handoff 제한: hop 초과 시 차단 + 폴백 실행
- 차단 케이스: 미등록 agent, 금지 method, stream 미지원

3. 회귀 테스트
- handoff 미발생 요청에서 기존 결과 동일성 유지
- 기존 circuit-breaker + swarm cooldown 정책과 충돌 없음
- 공통 progress 포맷 파싱/UI 렌더링 회귀 없음(`stage/progress/message/metadata`)

---

## 8) 완료 기준 (Definition of Done)

- handoff 지시가 있는 결과를 Supervisor가 동적으로 실행 계획에 반영한다.
- 무한 루프 없이 `maxHandoffHops` 내에서만 이관이 수행된다.
- 차단/허용 사유가 Swarm eventLog에 기록된다.
- non-handoff 경로의 기능 회귀가 없다.
- 운영 설정만으로 handoff on/off 및 제한값 조정이 가능하다.
- `handoff.enabled=false`일 때 기존 그래프 실행 결과와 동등성을 유지한다.
- `handoff.enabled=true/false` 전환 시 감사 로그에 토글 상태가 식별 가능하다.
- handoff 관련 public API/핵심 메서드에 Javadoc이 적용되어 코드리뷰 체크리스트를 통과한다.
- handoff 진행상태가 UI trace에 공통 포맷으로 노출된다.
- 리팩토링 코드 리뷰에서 SOLID/추상화/가독성/유지보수성 체크리스트를 통과한다.

---

## 9) 리스크 및 대응

- 리스크: handoff 남용으로 실행 경로 폭주
  - 대응: hop 제한 + 세션 레이트리밋 + 중복 경로 차단
- 리스크: planner 계획과 handoff가 충돌해 예측 가능성 저하
  - 대응: handoff 우선순위 규칙 문서화(`HANDOFF` sourceType 추적)
- 리스크: 하위 agent 응답 형식 편차로 directive 파싱 실패
  - 대응: 표준 필드 우선 + 보수적 파싱 + 실패 시 무조건 폴백

---

## 10) 운영 가이드(초기값 제안)

- `handoff.enabled=false` (초기 배포 기본값)
- `handoff.maxHops=3`
- `handoff.blockSameAgentWithinSteps=2`
- `handoff.maxPerMinute=10`
- `handoff.allowMethods=[SendMessage,message/send,SendStreamingMessage,message/stream]`

권장 롤아웃:

1. 1차: `enabled=false`로 배포하여 directive 관측만 수행 (`HANDOFF_SKIPPED_BY_FLAG` 모니터링)
2. 2차: canary session만 `enabled=true` 적용
3. 3차: 오류율/지연/루프 지표 이상 없으면 점진 확대
