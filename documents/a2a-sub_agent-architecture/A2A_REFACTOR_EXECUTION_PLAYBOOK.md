# A2A 리팩토링 실행 플레이북

작성일: 2026-04-10  
대상: `spring-ai`  
위치: `documents/a2a-architecture`

## 1. 목적

이 문서는 A2A 통합 품질을 `문서 정합성 + 운영 안정성 + 테스트 신뢰성` 기준으로 상향하기 위한 실행 가이드다.

핵심 목표:
- 기존 API(`/api/*-agent/*`)는 유지
- A2A API(`/a2a/*`)는 확장
- task lifecycle과 오케스트레이션 상태 전이의 일관성 강화
- 예외/취소/테스트 공백 보완

## 2. 범위

포함:
- A2A 컨트롤러/서비스/오케스트레이터/예외 처리
- 테스트(`A2aApiTest`, 오케스트레이션/상태전이 테스트)
- 문서 대비 불일치 해소

제외:
- 도메인 정책(허용 서버/툴) 자체 변경
- 기존 Planner/Executor 핵심 알고리즘 전면 교체
- 기존 UI 계약 파괴

## 3. 현재 확인된 주요 갭

1. A2A 표준 엔드포인트 표현(`/a2a`, `/a2a/stream`)과 구현 간 차이
2. A2A 요청 실패 시 JSON-RPC 오류 포맷 일원화 부족
3. `tasks/cancel`이 상태 저장 중심이며 실행 스트림 중단 반영이 약함
4. 상태 전이(E2E) 테스트 커버리지 부족
5. MCP 테스트의 비활성화 구간 다수
6. Agent Card가 고정 목록일 경우, 호스트가 실제 연결 의도(예: 2개만 연결)를 오인식할 가능성

## 4. 리팩토링 원칙

- Additive first: 기존 경로/흐름은 유지, 필요한 경로만 추가
- Boundary strict: scope ownership 위반은 즉시 차단
- Protocol isolation: A2A 계약은 `a2a.*` 패키지로만 처리
- Observable safety: 민감정보 로그 금지, 상태 전이는 테스트로 고정

## 4.1 하위 에이전트 독립성 원칙(필수)

- 각 하위 에이전트(`product`, `reservation`, `search`)는 같은 애플리케이션/서버에 공존해도 **독립 에이전트**로 취급한다.
- 동일 IP/호스트를 공유할 수 있으므로, 독립성은 IP가 아니라 **개별 endpoint 경계**로 보장한다.
  - 예: `/a2a/product`, `/a2a/reservation`, `/a2a/search`
- 컨트롤러 간 전이/포워딩/위임은 금지한다.
  - `product` 요청을 `reservation/search` 컨트롤러로 넘기지 않는다.
  - 내부적으로도 cross-controller 호출 경로를 만들지 않는다.
- task 접근은 생성된 scope와 동일한 경계에서만 허용한다.
  - scope 불일치 taskId는 즉시 차단한다.
- Agent Card 노출도 에이전트 경계를 따라 분리한다.
  - 호스트 에이전트가 등록한 하위 에이전트가 2개라면, 카드 조회 대상도 그 2개로 한정한다.
  - 특정 하위 에이전트의 `/.well-known/agent.json` 요청에는 해당 에이전트 카드만 포함되어야 하며, 선택되지 않은 다른 에이전트 카드는 노출되면 안 된다.

## 4.2 Base API 호환성 원칙(필수)

- `controller/base`는 기존 계약(`/api/*-agent/*`)만 유지한다.
  - 예: `/api/product-agent/*`, `/api/reservation-agent/*`, `/api/search-agent/*`
- `controller/base`에 A2A 경로(`/a2a/**`)를 추가하지 않는다.
- A2A 리팩토링은 `controller/a2a`, `a2a/*`, 서비스 계층에서만 수행한다.
- Base API는 기존 클라이언트의 무중단 호환성을 최우선으로 보장한다.

## 5. 작업 패키지

## 5.1 WP-A: A2A 엔드포인트 정합성 강화

대상 파일:
- `controller/a2a/*Controller`

작업:
- 기존 `/a2a/{scope}` 유지
- 선택적으로 `/a2a` 및 `/a2a/stream` 진입 별칭 컨트롤러 추가
- 별칭 컨트롤러는 scope를 요청 파라미터/헤더로 해석해 공통 서비스 라우터로 위임
- 단, 별칭 컨트롤러를 두더라도 최종 실행은 scope별 독립 endpoint 경계로 라우팅하며 컨트롤러 간 전이를 만들지 않는다.

코드 작성 방식:
- 신규 `AgentA2ARouterController` 생성
- 내부에서 `AgentScopeName` 파싱 후 `A2aScopeRouterService`(가칭) 호출
- 라우팅 실패는 `JsonRpcResponse.error(..., -32600 또는 -32602)` 반환
- `scope -> endpoint` 매핑은 고정 테이블로 유지하고 동적 체인 전이를 허용하지 않는다.
- `controller -> controller` 직접 호출/전이는 금지하고, 모든 분기는 서비스 계층에서만 처리한다.

예시 스케치:
```java
@RestController
@RequestMapping("/a2a")
class AgentA2ARouterController {
    @PostMapping
    JsonRpcResponse route(@RequestBody JsonRpcRequest req, HttpServletRequest raw, HttpSession session) { ... }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> routeStream(@RequestBody JsonRpcRequest req, HttpServletRequest raw, HttpSession session) { ... }
}
```

## 5.2 WP-B: A2A 예외 포맷 일원화

대상 파일:
- `advice/GlobalExceptionHandler`
- 필요 시 `a2a/dto/JsonRpcResponse`

작업:
- A2A 요청(`/a2a/**`)에서 발생한 예외는 JSON-RPC error envelope로 반환
- 일반 API(`/api/**`)는 기존 `ErrorResponse` 유지

코드 작성 방식:
- `HttpServletRequest`를 핸들러 파라미터로 받아 경로 분기
- A2A 분기에서 `ResponseEntity<JsonRpcResponse>` 반환
- 공통 에러코드 매핑 테이블 추가

예시 스케치:
```java
if (request.getRequestURI().startsWith("/a2a")) {
    return ResponseEntity.badRequest().body(JsonRpcResponse.error(id, -32602, "Invalid params"));
}
```

## 5.3 WP-C: cancel의 실행 반영 강화

대상 파일:
- `service/agent/orchestrator/AgentOrchestrator`
- `a2a/lifecycle/A2aLifecycleService`
- `a2a/task/A2ATaskStore`

작업:
- `tasks/cancel` 호출 후 해당 task가 취소 상태면 compose 스트림 조기 종료
- 완료 시점에 취소된 task는 `markCompleted`로 덮어쓰지 않음
- cancel 처리 중에도 cross-scope/cross-controller 조회나 위임은 금지한다.

코드 작성 방식:
- 오케스트레이터에 `isCanceled(a2aContext)` 헬퍼 추가
- 스트림 `doOnNext` 또는 `handle` 단계에서 취소 여부 점검 후 `Flux.error(new CancellationException(...))` 또는 `Flux.empty()` 종료
- `markA2aCompleted` 내부에 취소 상태 가드

예시 스케치:
```java
if (isCanceled(request.a2aContext())) {
    return Flux.just("요청이 취소되었습니다.");
}
```

## 5.4 WP-D: 상태 전이 테스트 보강

대상 파일:
- `src/test/java/com/example/springai/A2aApiTest.java`
- 신규: `src/test/java/com/example/springai/A2aLifecycleFlowTest.java`

작업:
- `message/send -> RUNNING -> COMPLETED` 검증
- 실패 시 `FAILED` 검증
- `cancel` 후 `CANCELED` 유지 검증
- scope ownership 위반 시 `NOT_FOUND` 또는 정책 오류 검증

코드 작성 방식:
- 테스트 전용 mock/fake 경로를 최소 주입
- 상태 조회는 `tasks/get`, 목록은 `tasks/list`로 검증

## 5.5 WP-E: 오케스트레이션 회귀 테스트 추가

대상 파일:
- 신규: `service/agent/orchestrator/AgentOrchestratorTest`
- 신규: `service/agent/graph/LangGraphAgentStateGraphFactoryTest`

작업:
- `PLAN_REQUIRED=false` 시 execute 스킵
- execute 반복 상한(4회) 검증
- persist(history/checkpoint) 호출 검증

코드 작성 방식:
- 포트 인터페이스를 mock하여 상태 전이만 단위 검증
- 스트림 인덱스 0(summary) 저장 제외 규칙 검증

## 5.6 WP-F: MCP 테스트 전략 재정렬

대상 파일:
- `src/test/java/com/example/springai/mcp/*`

작업:
- 현재 `@Disabled` 테스트는 분류:
  - 빠른 단위 테스트(기본 CI)
  - 느린 통합 테스트(프로파일/태그 기반)
- CI 기본 파이프라인에서 최소한의 MCP 계약 테스트는 항상 실행

코드 작성 방식:
- `@Tag("mcp-integration")`로 분리
- 무한 대기 방지를 위해 timeout 고정

## 5.7 WP-G: Agent Card 동적 노출/호스트 인식 안정화

대상 파일:
- `a2a/registry/AgentCardRegistry`
- `controller/a2a/AgentCardController`
- `model/agent/AgentScopeName` (필요 시 구조 조정)
- `test/A2aApiTest` (카드 노출 검증)
- 필요 시 `application*.yml`의 카드 노출 설정 섹션

작업:
- Agent Card는 하드코딩 3개 고정이 아니라, 현재 활성 스코프/설정 기준으로 동적 생성한다.
- 호스트 에이전트에 하위 에이전트 2개만 연결하는 경우, `/.well-known/agent.json`도 정확히 2개만 노출한다.
- 노출되지 않은 스코프 endpoint는 요청 시 명확히 실패(404 또는 정책 오류)하도록 통일한다.
- 카드의 `supportedInterfaces`는 실제 접근 가능한 endpoint만 포함한다.
- 동일 IP/동일 서버 환경에서도 endpoint path로 독립성을 표현한다.
- 호스트가 등록한 각 하위 에이전트 카드 URL을 개별 호출할 때, 응답은 단일 에이전트 카드(자기 자신)만 반환하도록 구성한다.
  - 예: host에 `product`, `reservation`만 등록된 경우
  - `product` 카드 URL 응답: `product` 정보만 포함
  - `reservation` 카드 URL 응답: `reservation` 정보만 포함
  - `search` 정보는 어떤 응답에도 포함되지 않음

코드 작성 방식:
- `AgentCardRegistry`는 `agent.scopes` 또는 별도 `agent.cards.enabled-scopes` 설정을 입력으로 사용한다.
- 카드 메타데이터(이름/설명/스킬) 템플릿은 scope key 기반으로 매핑하고, 최종 노출 목록은 설정 기반 필터링으로 구성한다.
- 컨트롤러 간 호출 없이, 카드 생성/필터링 로직은 서비스/레지스트리 계층에 한정한다.
- 테스트는 "3개 고정" 단언을 제거하고 "설정된 스코프와 정확히 일치"를 검증한다.
- 필요 시 카드 엔드포인트를 에이전트 단위로 분리한다.
  - 예: `/a2a/{scope}/.well-known/agent.json` 또는 동등한 독립 카드 URL 체계
  - 전역 카드 응답을 유지하더라도, 호스트 등록용 URL은 반드시 에이전트 단일 카드 응답을 보장한다.

예시 스케치:
```java
List<String> enabledScopes = cardExposureProperties.enabledScopes();
return enabledScopes.stream()
        .map(this::buildCardByScope)
        .flatMap(Optional::stream)
        .toList();
```

## 6. 파일별 수정 체크리스트

- `controller/a2a/*`: 라우팅/검증/에러코드
- `controller/base/*`: 기존 `/api/*-agent/*` 계약 불변 확인(경로/응답 스키마)
- `advice/GlobalExceptionHandler`: A2A JSON-RPC 에러 분기
- `service/agent/orchestrator/AgentOrchestrator`: cancel 반영, 완료 가드
- `a2a/task/*`: 취소 상태 우선 규칙
- `test/*`: lifecycle/graph 회귀 추가
- `a2a/registry/*`: 설정 기반 Agent Card 노출
- 모든 항목 공통: "컨트롤러 간 전이/포워딩 없음"을 코드 리뷰 체크포인트로 강제

## 7. 완료 기준(DoD)

1. 기존 `/api/*-agent/*` E2E 모두 통과
2. A2A 핵심 시나리오(`message/send`, `tasks/get`, `tasks/cancel`, `tasks/list`) 통과
3. cancel 후 상태 불변(CANCELED) 검증 통과
4. A2A 예외는 JSON-RPC 포맷으로 일관 응답
5. 오케스트레이션 상태 전이 테스트 통과
6. 기본 CI에서 MCP 최소 계약 테스트 실행
7. 컨트롤러 간 전이/포워딩이 없음을 정적 검사 및 테스트로 확인
8. `/.well-known/agent.json`(또는 호스트 등록용 카드 URL) 노출 개수/항목이 활성 스코프 설정과 정확히 일치
9. 하위 에이전트를 2개만 활성화한 프로파일에서 호스트 인식 회귀 테스트 통과
10. 하위 에이전트별 카드 URL을 각각 조회했을 때, 각 응답은 해당 에이전트 카드만 포함하고 비선택 에이전트 정보는 0건
11. `controller/base`의 기존 `/api/*-agent/*` 경로가 변경 없이 유지되고 회귀 테스트 통과

## 8. 적용 순서(권장)

1. WP-B (예외 포맷)
2. WP-C (cancel 반영)
3. WP-D (A2A 테스트)
4. WP-E (오케스트레이션 테스트)
5. WP-A (표준 별칭 경로, 필요 시)
6. WP-F (MCP 테스트 재정렬)
7. WP-G (Agent Card 동적 노출)

## 9. 리스크와 완화

- 리스크: 예외 포맷 분기 추가 시 기존 API 응답 영향
- 완화: `/a2a/**` 경로 분기만 적용, `/api/**` 회귀 테스트 동시 실행

- 리스크: cancel 조기 종료 로직이 정상 완료 흐름을 오탐
- 완화: task 상태 조회 시 scope/taskId 일치 검증 + 통합 테스트로 고정

- 리스크: 카드 동적화 시 문서/운영 설정 불일치로 카드 누락 또는 과다 노출
- 완화: 프로파일별 카드 스냅샷 테스트(2개/3개 케이스)와 부팅 시 설정 검증 로그 추가

---

이 문서는 “무엇을 바꿀지”가 아니라 “어떻게 코드를 작성할지”까지 포함한 실행용 기준선이다.  
구현 단계에서는 각 WP를 독립 커밋으로 분리해 롤백 가능성을 확보한다.
