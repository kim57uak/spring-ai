# 30. Supervisor HITL + Graph/Swarm State 하이브리드 리팩토링 기획문서

## 1) 목적/범위

본 문서는 현재 `com.example.springsupervisorai` 구현을 분석한 뒤, Supervisor에 HITL(Human-in-the-Loop)을 도입하고 `Graph + Swarm State` 하이브리드 구조로 리팩토링하기 위한 실행 계획을 정의한다.

- 목적: 고위험 요청에서 사람 승인 기반 제어를 추가하고, 상태 분리를 통해 확장성과 운영 안정성을 강화
- 범위: Supervisor 경계(`controller/service/orchestrator/model/store`) 및 A2A task lifecycle
- 제외: 하위 에이전트(`product/reservation/search/mygenie`) 내부 비즈니스 로직
- 대상 경로: `/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai`

---

## 2) 기존 소스 분석 요약 (AS-IS)

### 현재 강점

- Graph 오케스트레이션이 이미 존재
  - `PLAN -> SELECT -> INVOKE -> MERGE -> COMPOSE` 상태 그래프 구현
  - 근거: `service/agent/graph/LangGraphSupervisorStateGraphFactory`
- A2A 호출 안정성 장치 보유
  - retry/backoff/circuit-breaker 적용
  - 근거: `service/agent/invoke/DefaultA2AInvocationService`
- 세션 소유권 기반 task 접근 제한 적용
  - `tasks/get|list|cancel`에서 sessionId 필터링
  - 근거: `a2a/lifecycle/SupervisorA2aLifecycleService`, `controller/SupervisorA2AController`
- idempotency 키에 `sessionId` 포함
  - 근거: `a2a/idempotency/SupervisorRequestIdempotencyService`

### 구조적 갭

1. HITL 상태/흐름 부재
- `SupervisorRuntimeState`에 승인 대기/승인/반려 상태가 없음
- 오케스트레이터에 human review 분기 노드가 없음

2. Checkpoint가 최소 문자열 수준
- 현재 `state=...;at=...` 형태로만 저장
- 승인 이력, policy 평가결과, 재실행 포인터를 담지 못함

3. 상태 책임이 단일 컨텍스트에 집중
- `SupervisorPlanningContext`가 실행 상태와 공유 컨텍스트를 동시에 담당
- 다중 에이전트 병렬/재진입 시 충돌 제어(버전, lock, event log) 모델이 부족

4. HITL 운영 인터페이스 부재
- 승인/반려/수정 API, SLA timeout, auto-escalation 정책 없음

---

## 3) 목표 아키텍처 (TO-BE)

- Graph: 실행 순서/분기/재시도를 통제
- Swarm State: 공유 컨텍스트, 승인 이력, 의사결정 로그를 저장
- HITL: 정책기반 리뷰 게이트와 인간 승인 워크플로우 제공

핵심 원칙:

- Graph는 제어 흐름 전용
- Swarm State는 데이터 정합성/재시작 복원 전용
- 정책 평가(HITL)는 side-effect 없는 순수 판단 + 이력 저장 분리

---

## 4) 리팩토링 설계 항목

### A. Runtime State 확장

- `SupervisorRuntimeState`에 아래 상태 추가
  - `HITL_EVALUATING`
  - `HITL_WAITING`
  - `HITL_APPROVED`
  - `HITL_REJECTED`
  - `HITL_TIMEOUT`

### B. Graph 노드 확장

- 신규 노드
  - `RISK_ASSESS`: 리스크/정책 평가
  - `HITL_GATE`: 승인 필요 여부 분기
  - `WAIT_REVIEW`: 승인 대기/재개
  - `APPLY_REVIEW`: 승인/취소 반영(이번 차례)

### C. Swarm State 저장소 도입

- 신규 포트
  - `SupervisorSwarmStateStore`
  - `SupervisorReviewStore`
- 저장 모델
  - `stateVersion`, `lockOwner`, `eventLog`, `hitlDecision`, `resumeFromNode`

### D. HITL 정책 엔진 도입

- 신규 서비스
  - `HitlPolicyService`: 승인 필요 여부/사유/우선순위 계산
  - `HitlDecisionService`: `approve/cancel` 처리(이번 차례 범위)
- 입력 시그널
  - plan confidence
  - 민감 키워드/도메인
  - 금액/개인정보/법률성 요청
  - 하위 에이전트 실패율/서킷오픈 상태
  - 데이터 생성/변경 요청 여부(create/update/delete)

#### 데이터 생성 요청 강제 HITL 규칙

- 아래 요청은 리스크 점수와 무관하게 `HITL required=true`로 강제한다.
  - 상품 생성/등록/수정/삭제
  - 예약 생성/변경/취소
  - 주문/결제/환불 등 영속 데이터 변경
- 판별 방식(MVP)
  - 라우팅 plan의 `intent`/`arguments`/`agentKey` + 키워드 기반 휴리스틱
  - 예: `create`, `register`, `book`, `reserve`, `insert`, `save`, `cancel reservation`
- 정책 결과
  - `policyId=HITL-POL-DATA-MUTATION`
  - `action=WAIT_REVIEW`
  - `onTimeout=reject`(기본), 필요 시 서비스별 fallback 메시지

#### 이번 차례 구현 범위 (Scope Freeze)

- HITL 결정 타입은 `APPROVE`, `CANCEL`만 지원한다.
- `REVISE`(검토자가 plan/파라미터 수정)는 이번 차례에서 제외한다.
- 구현 우선순위는 승인/취소 상태 전이 안정화와 재개/중단 일관성 확보에 둔다.

### E. API 계약 확장

- A2A 계약은 `legacy`와 `v1.0` 기준을 모두 충족하도록 설계한다.
  - 메서드 호환은 enum 기반(`SupervisorA2aMethod`)으로 중앙 관리한다.
- JSON-RPC method 추가
  - `tasks/review/get`
  - `tasks/review/decide`
- 기존 `tasks/get` 결과에 `hitl` 블록 포함

---

## 5) 리팩토링 기준 (코드 품질/설계 원칙)

### A. 추상화(Abstraction) 기준

- Controller는 프로토콜 변환/검증만 담당하고 도메인 규칙은 Service/Orchestrator로 이동
- Orchestrator는 그래프 제어만 담당하고, 정책/저장은 별도 인터페이스로 분리
- 외부 의존(Redis/A2A Client/LLM)은 Port-Adapter 구조로 캡슐화

### B. SOLID 준수 기준

- `S`: 클래스당 단일 책임 유지(예: HITL 정책 판단과 HITL 결정 반영 분리)
- `O`: 신규 정책/노드 추가 시 기존 핵심 흐름 수정 최소화
- `L`: 포트 인터페이스 구현체 교체 가능성 보장
- `I`: 작은 인터페이스로 분리(`HitlPolicyService`, `HitlDecisionService`, `SwarmStateStore`)
- `D`: 상위 모듈은 구현체가 아닌 추상(인터페이스)에 의존

### C. 간결성(Simplicity) 기준

- 중복 분기/문자열 하드코딩 제거(enum/상수/정책 객체로 치환)
- 메서드 길이와 분기 복잡도 제한(읽기 중심 코드 유지)
- "한 번에 한 가지 일" 원칙으로 노드 액션 단순화

### D. Spring Boot 디자인 패턴 유지 기준

- 계층 경계 유지: `controller -> service -> orchestrator -> port -> adapter`
- 설정 외부화 유지: `@ConfigurationProperties` 기반 정책 주입
- 예외 처리 일원화: `GlobalExceptionHandler`에서 JSON-RPC 에러 변환
- Bean 주입은 생성자 주입 원칙 고수

### E. Java 코드 관례 유지 기준

- 네이밍: 클래스 PascalCase, 메서드/필드 camelCase, 상수 UPPER_SNAKE_CASE
- 불변성 우선: 가능한 `final`, record/value object 적극 사용
- null-safe 처리 명시(빈 문자열/Optional 정책 일관화)
- Javadoc 유지: public 타입/핵심 메서드의 책임/입출력/예외 의도 명시
- 테스트 네이밍/구조 일관화: given-when-then 스타일 유지

### F. 검증 체크리스트

1. 신규 클래스가 단일 책임을 벗어나지 않았는가
2. 인터페이스 없이 구현체 직접 의존한 지점이 없는가
3. 컨트롤러에 비즈니스 로직이 유입되지 않았는가
4. 설정값 하드코딩 없이 yml/properties로 외부화되었는가
5. Java/Spring 네이밍 및 코드 스타일이 기존 코드베이스와 일치하는가

---

## 6) 단계별 실행 계획

### Phase 1 (P0): 도메인/상태 확장

- `model` 패키지에 HITL 상태/결정 모델 추가
- `SupervisorPlanningContext`를 `GraphExecutionState + SwarmSharedState`로 분리
- checkpoint payload를 구조화 JSON으로 전환

### Phase 2 (P1): Graph 분기 + 오케스트레이터 리팩토링

- 그래프 노드 추가(`RISK_ASSESS/HITL_GATE/WAIT_REVIEW/APPLY_REVIEW`)
- `SupervisorAgentOrchestrator`에서 WAIT 상태를 non-blocking resume 방식으로 변경
- 승인 완료 시 지정 노드부터 재개

### Phase 3 (P1): HITL API/스토어 구현

- review 조회/결정 API 추가
- decision 이력/감사로그 저장
- SLA timeout 시 자동 `reject` 또는 `fallback` 정책 적용

### Phase 4 (P2): 운영 안정성 강화

- 상태 버전 충돌 감지(optimistic lock)
- idempotent decision 처리(`decisionId`)
- 장애 복구 리플레이 테스트 추가

---

## 7) 변경 대상 파일(우선순위)

1. 필수 수정
- `model/SupervisorRuntimeState.java`
- `model/SupervisorGraphState.java`
- `model/SupervisorPlanningContext.java`
- `service/agent/graph/LangGraphSupervisorStateGraphFactory.java`
- `service/SupervisorAgentOrchestrator.java`
- `a2a/task/A2aTaskStatus.java`
- `a2a/task/A2aTaskSnapshot.java`

2. 신규 추가
- `service/agent/hitl/HitlPolicyService.java`
- `service/agent/hitl/HitlDecisionService.java`
- `service/agent/store/SupervisorSwarmStateStore.java`
- `service/agent/store/redis/RedisSupervisorSwarmStateStore.java`
- `a2a/dto/TaskReview*.java`

3. API/검증
- `controller/SupervisorA2AController.java`
- `controller/SupervisorA2ARequestValidator.java`

---

## 8) 테스트 전략

1. 단위 테스트
- 정책 평가(승인 필요 여부) 결정성 검증
- 상태 전이(`HITL_WAITING -> APPROVED/CANCELED/TIMEOUT`) 검증

2. 통합 테스트
- `message/send`에서 HITL 트리거 시 승인 대기 반환
- 승인 후 재개되어 `COMPLETED` 종료
- 취소 시 compose 미실행/정상 에러 매핑
- 상품/예약 생성 시나리오에서 무조건 `WAITING_REVIEW` 전이 검증

3. 회귀 테스트
- 기존 non-HITL 요청 경로 성능/기능 회귀 없음
- 기존 `tasks/get|list|cancel` 호환 유지

---

## 9) 완료 기준 (Definition of Done)

- Graph에 HITL 분기 노드가 포함되고 운영 시나리오(승인/취소/타임아웃) 동작
- Swarm State에 승인 이력 + 재개 포인터 + 버전 정보가 저장됨
- `tasks/review/get`, `tasks/review/decide` 계약 테스트 통과
- 기존 supervisor 회귀 테스트 통과
- 데이터 생성 요청(create/update/delete)은 항상 HITL 정책으로 차단/승인 후 진행됨

---

## 10) 향후 계획 (Next Phase)

- 사용자 추가정보 수집 인터랙션 도입
  - 예: 이름/전화번호/이메일 입력 요청 후 검증
  - 사용자 입력은 자연어/콤마 형태를 허용하고 내부에서 구조화 DTO로 변환
- 라우팅 계획 의존성 모델 도입(`dependsOn` 또는 `stage/group`)
  - 선후관계가 있는 plan은 순차 실행을 강제하고, 의존성이 없는 plan만 제한적 병렬 실행(`max-concurrency` 상한)으로 스케줄링
  - 실행 로그에 `blockedByDependency`, `readyBatch`, `executionOrder`를 남겨 원인 분석 가능성 확보
- `tasks/review/decide` 확장 결정 타입 `REVISE` 지원
  - 검토자 수정 파라미터를 반영해 재계획 재진입

---

## 11) 리스크 및 대응

- 리스크: 승인 대기 누적으로 큐 정체
  - 대응: 우선순위 큐 + SLA timeout + auto-close 정책
- 리스크: 승인 후 재개 시 중복 실행
  - 대응: `resumeToken + stateVersion + idempotency key` 3중 검증
- 리스크: 상태 스키마 변경으로 복구 실패
  - 대응: `schemaVersion` 도입 + 하위호환 역직렬화 처리
