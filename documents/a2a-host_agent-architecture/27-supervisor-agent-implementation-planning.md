# 27. Supervisor Agent 생성 기획문서

## 1) 목적/범위

본 문서는 `a2a-host_agent-architecture` 하위 설계(01~26)를 기준으로, `Supervisor Agent`를 구현하기 위한 실행 계획을 정의한다.

- 목적: `/a2a/*` 단일 진입점 기반 Supervisor를 안정적으로 구축
- 범위: `Spring AI + LangGraph4j + Redis + A2A(JSON-RPC/SSE)` 조합으로 `plan -> select -> invoke -> merge -> compose` 플로우 구현
- 제외: 하위 Agent 내부 로직/툴/MCP 구현 상세
- 코드 생성 위치: `/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai` 하위 폴더를 Supervisor Agent 전용 루트로 사용
- 호환 요구: 기존 `com.example.springai`와 신규 `com.example.springsupervisorai`를 동시에 Bean Scan 대상으로 포함
- 참고 원칙: 기존 `com.example.springai` 하위 하위에이전트 코드는 구현 패턴(코딩 스타일, 계층 분리, 예외 처리 방식) 참고용으로만 활용

### 30/31 기준 본문 통합 반영

- 오케스트레이션은 `Graph + Swarm State` 하이브리드로 확장한다.
- HITL은 이번 차례에서 `APPROVE/CANCEL`만 구현한다.
- 상품/예약/주문 등 데이터 생성·변경 요청은 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy + v1.0` 동시 호환을 유지한다(`SupervisorA2aMethod` enum 기준).

---

## 2) 아키텍처 원칙 (SOLID + 소프트웨어 공학)

### 핵심 설계 원칙

- `S` (Single Responsibility)
  - `Controller`: 프로토콜 어댑터(JSON-RPC 파싱/검증/응답 envelope)
  - `Service/Orchestrator`: 유스케이스 조합 및 상태 진행
  - `Plan/Invoke/Compose`: 각 책임을 포트 단위로 분리
- `O` (Open/Closed)
  - 새 하위 Agent 추가 시 `a2a-supervisor.yml` 및 `A2AClientRegistry` 확장으로 대응
  - 오케스트레이션 코어 수정 최소화
- `L` (Liskov Substitution)
  - `SupervisorPlanningService`, `A2AInvocationService`, `SupervisorResponseComposeService` 인터페이스 대체 가능성 보장
- `I` (Interface Segregation)
  - 계획/호출/합성/스토어/그래프 포트를 분리하여 변경 파급 최소화
- `D` (Dependency Inversion)
  - Orchestrator는 구현체가 아닌 포트(interface)에 의존

### 유지보수/가독성 원칙

- 패키지 정책 고정: `controller -> service -> orchestrator -> ports -> adapters`
- 구현 루트 고정: `src/main/java/com/example/springsupervisorai` 하위에서만 Supervisor 관련 코드 생성/수정
- 부트스트랩 정책: `SpringAiApplication`은 유지하고 `@SpringBootApplication(scanBasePackages=...)`로 `springai`, `springsupervisorai` 동시 스캔
- 분리 운영 정책: Supervisor는 `springsupervisorai` 경계에서 독립적으로 개발/배포/운영 가능해야 하며, `springai` 코드에 컴파일/런타임 의존을 추가하지 않는다
- 주석 정책: 모든 클래스/핵심 메서드에 Javadoc 포맷의 상세 주석을 작성하고, 책임/입력/출력/예외 처리 의도를 명시한다
- 상수 정책: 코드성 하드코딩 문자열(method/state/error/node)은 직접 literal 사용을 금지하고 기능별 `enum`으로 중앙 관리한다
- `/a2a/*` 외 별도 컨트롤러 추가 금지
- 하위 Agent 호출은 반드시 `A2AInvocationService`를 통해서만 수행
- 예외 응답은 `GlobalExceptionHandler`에서 일괄 정규화(민감정보 비노출)
- 테스트는 계약 기반(`message/send`, `message/stream`, `tasks/*`)으로 회귀 자동화

---

## 3) 구현 목표 상태 (Target State)

- 단일 진입점: `SupervisorA2AController`
- 오케스트레이션: `LangGraph` 상태 기반 실행 + iteration guard + checkpoint resume
- 라우팅: `a2a-supervisor.yml` allowlist + method allowlist + timeout/retry/circuit-breaker
- 응답: 부분 실패 허용(partial result) + 최종 compose 품질 확보
- 저장: Redis conversation/checkpoint 일원화
- 관측성: latency, failure rate, token/cost, downstream별 성공률 지표 확보

---

## 4) 작업 순서 (Implementation Order)

### Step 0. 사전 정리

- 패키지 구조를 `17, 20` 문서 기준으로 정렬하되, 물리 경로는 `src/main/java/com/example/springsupervisorai` 하위로 고정
- `SpringAiApplication`의 컴포넌트 스캔 범위에 `com.example.springsupervisorai` 포함 여부 확인/적용
- `com.example.springai`의 기존 하위에이전트 코드에서 참고할 구현 패턴(네이밍/예외흐름/테스트 구조)만 식별
- 기존 컨트롤러/서비스 책임 경계 점검
- 기본 모델(`Request/Context/RoutingPlan/Result/GraphState`) 확정

### Step 1. 진입점/프로토콜 안정화 (P0)

- `SupervisorA2AController` 단일 경로(`/a2a/*`) 확정
- JSON-RPC `method`/`params` 입력 검증 및 allowlist 적용
- 응답 envelope 직렬화 규칙 통일(`send/stream/tasks`)
- `GlobalExceptionHandler` A2A 오류 코드 매핑 표준화

### Step 2. 오케스트레이션 코어 구축 (P0~P1)

- `SupervisorAgentService -> SupervisorAgentOrchestrator` 경로 확립
- `plan -> select -> invoke -> merge -> compose` 상태 그래프 구현
- `more plans` 반복 처리와 `max iteration guard` 적용
- `history/checkpoint` 로드/저장 연결

### Step 3. 라우팅/호출 경계 구축 (P1~P2)

- `A2AClientRegistry` + `A2AJsonRpcClient` 구현
- `a2a-supervisor.yml` 기반 endpoint/method/timeout/retry 외부화
- endpoint allowlist + payload size guard + circuit-breaker 적용

### Step 4. 응답 합성/스트리밍 품질 개선 (P3)

- `SupervisorResponseComposeService` 스트리밍 합성 고도화
- 실패 시 fallback/partial result 정책 적용
- 스트림 chunk framing/종료 이벤트/취소 처리 규칙 고정

### Step 5. 검증/운영 강화 (P4)

- 계약 테스트: `message/send`, `message/stream`, `tasks/*`
- 회귀 테스트: 정상/오류/부분실패/취소/timeout 시나리오
- 관측 지표 대시보드 및 알람 룰 설정

---

## 5) 태스크 과정 상세 (WBS)

1. 도메인/인터페이스 확정
- `SupervisorPlanningService`, `A2AInvocationService`, `SupervisorResponseComposeService` 시그니처 고정
- `SupervisorPlanningContext` 상태 필드 및 checkpoint key 전략 정의
- `springai` 기존 구조는 코드 패턴 참고만 수행하고, Supervisor 구현 코드는 `springsupervisorai`에 독립 작성

2. 컨트롤러 리팩토링
- precheck(jsonrpc/method/params), service 위임, envelope serialization만 담당
- orchestration/라우팅 분기 제거

3. 애플리케이션 스캔 설정
- `SpringAiApplication` 이동 없이 `scanBasePackages` 또는 동등 설정으로 `com.example.springai`, `com.example.springsupervisorai` 동시 등록
- `@ConfigurationPropertiesScan` 대상도 신규 패키지 설정 클래스까지 반영되는지 검증

4. 그래프 실행기 구현
- 노드별 책임 분리: `PLAN_INTENT`, `SELECT_DOWNSTREAM`, `CALL_A2A`, `MERGE_RESULTS`, `COMPOSE_RESPONSE`
- 루프 종료 조건 및 예외 전이(HUMAN_MESSAGE) 반영

5. A2A 호출 어댑터 구현
- registry resolve -> client call -> result normalize 파이프라인
- downstream 오류를 `DownstreamCallResult`로 정규화

6. 저장소/복구 구현
- 대화 히스토리 저장 정책(요약/원문) 결정
- checkpoint resume 시 state integrity 검증

7. 보안/안정성 가드
- PromptInjectionGuard, endpoint/method allowlist, payload size guard
- timeout/retry/circuit-breaker 정책을 supervisor 경계에 일원화

8. 테스트/관측성 구현
- 단위 테스트(포트/어댑터), 통합 테스트(컨트롤러~오케스트레이터), 계약 테스트(A2A)
- 구조화 로그 + trace id + 핵심 metric(latency/failure/token/cost)

9. 코드 품질 표준화
- 클래스/메서드 Javadoc 작성 기준(역할, 파라미터, 반환, 오류) 적용
- method/state/error/node 등 코드성 문자열을 enum으로 치환하고 중앙 관리 정책 준수

---

## 6) 산출물 (Deliverables)

- 코드
  - 생성 경로: `/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai`
  - `SupervisorA2AController`, `SupervisorAgentService`, `SupervisorAgentOrchestrator`
  - `LlmSupervisorPlanningService`, `DefaultA2AInvocationService`, `LlmSupervisorResponseComposeService`
  - `LangGraphSupervisorStateGraphFactory`, `A2AClientRegistry`, `A2AJsonRpcClient`
- 설정
  - `a2a-supervisor.yml` 라우팅/재시도/타임아웃 정책
- 테스트
  - 정상/오류/취소/부분실패/streaming 계약 테스트 세트
- 운영
  - 예외 코드 매핑표, 모니터링 지표 정의서, 장애 대응 런북

---

## 7) 완료 기준 (Definition of Done)

- 아키텍처 준수
  - `/a2a/*` 단일 진입점 유지, 컨트롤러 비즈니스 로직 0
  - Orchestrator는 포트(interface)만 의존
- 기능 준수
  - `message/send`, `message/stream`, `tasks/*` 회귀 통과
  - multi-agent + fallback + partial-result 시나리오 통과
- 품질 준수
  - 예외 응답 sanitize 100%
  - timeout/retry/circuit-breaker 정책 동작 검증
  - 가독성 기준: 클래스 책임 단일화, 메서드 복잡도 제한, 네이밍 일관성 확보

---

## 8) 리스크 및 대응

- 리스크: 라우팅 규칙 증가로 분기 복잡도 상승
  - 대응: `RoutingPlan` 중심 데이터 흐름 유지, 분기 로직은 그래프 노드로 캡슐화
- 리스크: 스트리밍/취소 처리 불일치
  - 대응: chunk framing 및 종료 이벤트 규약 테스트를 계약화
- 리스크: 하위 Agent 장애 전파
  - 대응: circuit-breaker + fallback/partial result + 표준 오류코드 매핑

---

## 9) 문서 기준 추적성 (Traceability)

- 시스템 컨텍스트/컴포넌트/도메인: `01, 03, 04`
- 오케스트레이션/시퀀스/상태머신: `05, 06, 07, 11, 19`
- 예외/보안/배포: `08, 09, 10`
- 기술결정/로드맵/정책: `12, 13, 14, 15`
- 패키지/클래스/의사코드/라우팅정책: `17, 18, 20, 21, 24`
- 컨트롤러 개선/우선순위/리팩토링: `23, 25, 26`

---

## 10) 최근 반영사항 (2026-04-12)

### A. 하위 Agent Card 기반 라우팅 컨텍스트 도입

- `DownstreamAgentCardCache`를 추가해 Supervisor 부팅 시 `host.a2a.routing`에 등록된 각 하위 에이전트의 `/.well-known/agent.json`을 조회/캐시한다.
- 캐시된 card 요약(`name/description/version/skills/streaming`)을 planning prompt 변수 `{agentCards}`로 주입한다.
- planning 단계는 하드코딩된 에이전트 설명 대신 card 기반 메타데이터를 참조해 라우팅 결정을 수행한다.

### B. method 하드코딩 축소 및 streaming 정책 정합화

- `plans[*].agentKey`는 고정 목록이 아니라 `{allowedAgents}` 기준으로 해석하도록 프롬프트 계약을 정리했다.
- `message/stream`은 전역 허용하되, `agent card`의 `streaming=true`일 때만 유지하고, 아닌 경우 `message/send`로 자동 다운그레이드한다.
- 이 정책으로 신규 agent 등록 시 코드 하드코딩 없이 `a2a-supervisor.yml` + agent card 정보만으로 확장 가능성을 확보한다.

### C. 하위 message/stream 호출 경로 보강

- `A2AJsonRpcClient`에 스트림 호출 경로(`Accept: text/event-stream`)를 추가하고 SSE 청크를 병합해 payload로 반환한다.
- `DefaultA2AInvocationService`는 plan method가 `message/stream`일 때 스트림 경로를 사용하고, 그 외는 기존 JSON-RPC unary 경로를 유지한다.
- 기존 `message/send` 경로와의 역호환을 유지하며, 하위 agent 도구 실행 품질 저하 이슈를 완화한다.

### D. fallbackPlan 제거 정책 반영

- planner의 휴리스틱 fallbackPlan(검색/상품/예약 하드코딩)을 제거했다.
- planning 1차 + repair 모두 실패 시 빈 계획으로 종료하며, 임의 하드코딩 라우팅을 수행하지 않는다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
