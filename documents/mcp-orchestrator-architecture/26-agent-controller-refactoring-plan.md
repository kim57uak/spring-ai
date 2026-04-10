# Agent Controller 리팩토링 작업계획서

작성일: 2026-04-10  
대상 프로젝트: `spring-ai`

## 1. 목적

기존 `HttpChatController`의 동작을 보존하면서, 도메인별 MCP 사용 범위를 강제하는 신규 컨트롤러 3개를 추가한다.

- `ProductAgentController`
- `ReservationAgentController`
- `SearchAgentController`

각 컨트롤러는 다음 4개 엔드포인트를 제공한다.

1. `POST /stream` (스트리밍 응답)
2. `POST /chat` (한 번에 응답)
3. `POST /clear` (세션 초기화)
4. `GET /status` (세션 상태 확인)

## 2. 요구사항 요약

### 2.1 공통

- Spring AI + LangGraph4j 기반 체인/상태관리 유지
- 기존 에이전트 오케스트레이션 로직 보존
- SOLID, 유지보수성, 가독성 중심 리팩토링

### 2.2 도메인별 MCP 범위

- Product Agent
  - 서버: `sale-product`
  - 호스트: `http://10.225.18.50:8080`
  - 전송방식: `SSE` (HTTP 스트리밍 MCP)
  - 허용 툴: `createAutoCopySaleProducts`, `getSaleProductDetails`

- Reservation Agent
  - 서버: `reservation`
  - 호스트: `http://10.225.18.50:8080`
  - 전송방식: `SSE` (HTTP 스트리밍 MCP)
  - 허용 툴: `createReservation`

- Search Agent
  - 서버: `search-mcp-server`
  - 실행방식: `command/args` 기반 stdio
  - 기존 설정 기반 실행

## 3. 현재 구조 분석(요약)

- Controller: `HttpChatController`가 `/stream`, `/clear`, `/status` 제공
- Service: `HttpChatService`가 `AgentOrchestrator` 호출
- Orchestrator: `LangGraphAgentStateGraphFactory`를 통해 `plan -> execute -> compose`
- Planning: `HeuristicPlanningService`가 MCP 서버/툴 카탈로그를 LLM에 전달 후 ToolPlan 생성
- Execution: `McpToolExecutionService`가 실제 MCP 툴 호출

현재는 요청 단위의 "허용 서버/허용 툴 스코프" 개념이 없다.

## 3.1 신규 3개 에이전트 구조 원칙

신규 `Product/Reservation/Search` 에이전트도 아래 동일한 레이어 구조를 유지한다.

1. Controller: `/stream`, `/chat`, `/clear`, `/status`
2. Service: 세션/요청 조율, Orchestrator 위임
3. Orchestrator: LangGraph 그래프 실행 (`plan -> execute -> compose`)
4. Planning: MCP 서버/툴 카탈로그 생성 후 ToolPlan 산출
5. Execution: ToolPlan 기반 MCP 실제 호출

## 3.2 기존 HttpChatController 접근 정책

기존 `HttpChatController`는 하위호환을 위해 **모든 MCP 서버/툴 접근 가능** 정책을 유지한다.

- `HttpChatController`: unrestricted scope (all servers/tools)
- `Product/Reservation/Search` 컨트롤러: restricted scope (`allowedServers`, `allowedToolsByServer`)

## 4. 목표 아키텍처

## 4.1 핵심 개념: Agent Scope

요청 단위로 MCP 접근 가능 범위를 명시하는 `AgentScope`를 도입한다.

- `allowedServers: Set<String>`
- `allowedToolsByServer: Map<String, Set<String>>`

위 두 필드는 신규 3개 에이전트의 필수 제약 조건으로 사용한다.
`HttpChatController` 요청은 예외적으로 전체 허용 스코프를 사용한다.

## 4.2 적용 지점

### 1) Planning 단계 필터링

- 서버 카탈로그 생성 시 scope에 없는 서버 제외
- scope에 없는 툴은 LLM 선택 결과에서 제거

### 2) Execution 단계 강제

- 실제 MCP 호출 직전 scope 재검증
- 범위 밖 요청은 차단(로그 + 안전한 결과 반환)

> 설계 원칙: 계획 단계 + 실행 단계 이중 방어

## 5. 리팩토링 설계 상세

## 5.1 모델/컨텍스트 확장

- `AgentChatRequest`에 scope 정보 추가
- `AgentGraphState`, `PlanningContext`에 scope 전달 필드 추가

## 5.2 서비스 계층 분리

- 기존 `HttpChatService`는 유지 (하위호환)
- 신규 `ScopedAgentChatService` 추가
  - `Flux<String> streamChat(scope, sessionId, message, model)`
  - `Mono<String> syncChat(scope, sessionId, message, model)`  
    (stream collect 후 단일 문자열 반환)
  - `clearSession`, `getMessageCount` 공통 제공

## 5.3 컨트롤러 계층 구성

- `BaseAgentControllerSupport` (공통 메서드)
  - `stream`, `chat`, `clear`, `status` 로직 공통화
- 도메인 컨트롤러는 scope만 정의
  - `ProductAgentController`
  - `ReservationAgentController`
  - `SearchAgentController`

## 5.4 설정 전략

MCP 서버/툴 설정은 `application.yml`에서 분리하여 `mcp.yml`로 관리한다.

- `application.yml`:
  - `spring.config.import`에 `optional:classpath:mcp.yml` 추가
  - 공통 애플리케이션 설정만 유지
- `mcp.yml`:
  - `mcp.servers.*`(서버/transport/host/command/args/env/capabilities/allow-tools) 전담
  - 도메인 스코프(`allowedServers`, `allowedToolsByServer`) 외부화 포인트 제공

- 예: `agent.scopes.product.*`, `agent.scopes.reservation.*`, `agent.scopes.search.*`
- HTTP MCP 사용 시 예시:
  - `agent.scopes.product.host=http://10.225.18.50:8080`
  - `agent.scopes.reservation.host=http://10.225.18.50:8080`
- `allowed-servers`는 YAML 배열 구문을 권장한다.
  - 예: `allowed-servers: [sale-product, reservation]`
  - 환경변수/프로퍼티 문자열로 주입 시 콤마 구분도 바인딩 가능하다.

## 5.5 MCP 함수정보(툴 스키마) 조회/캐시 전략

MCP 서버 특성상 서버가 수시 재시작될 수 있으므로, 함수정보는 "필요 시 재연결 조회"를 기본으로 한다.

1. Planning/Execution 시점에 서버 연결 확인 후 함수정보 조회 시도
2. 캐시는 보조 수단으로 사용(짧은 TTL + 서버별 캐시)
3. 연결/조회 실패 시:
   - 직전 캐시가 있으면 임시 사용
   - 캐시도 없으면 안전하게 도구 미사용 경로로 폴백
4. 서버 재기동 후 재연결 성공 시 캐시 즉시 갱신
5. 캐시 키는 충돌 방지를 위해 복합키를 사용한다.
   - 권장 키 구성: `transport|serverName|endpointOrCommandSignature|toolSetHash|scopeHash`
   - 예시:
     - SSE: `sse|sale-product|http://10.225.18.50:8080/sse|<tools-hash>|<scope-hash>`
     - stdio: `stdio|search-mcp-server|/opt/homebrew/bin/node:<script-path>|<tools-hash>|<scope-hash>`
   - `serverName` 단독 캐시는 금지한다.

## 6. 구현 단계

1. `AgentScope` 및 관련 컨텍스트 모델 추가
2. `AgentOrchestrator` 입구에서 scope를 상태로 전달
   - `HttpChatController`: 전체 허용 스코프 전달
   - 신규 3개 컨트롤러: 제한 스코프 전달
3. `HeuristicPlanningService`에 scope 필터 적용
4. `McpToolExecutionService`에 scope 강제 검증 추가
5. MCP 함수정보 조회 로직을 "재연결 우선 + 캐시 보조" 방식으로 보강
6. `mcp` 설정을 `application.yml` -> `mcp.yml` 분리
7. `ScopedAgentChatService` 생성 및 sync/stream 공통 처리
8. 신규 컨트롤러 3종 생성 + `clear/status` 포함
9. 기존 `HttpChatController` 회귀 보장(변경 최소화)
10. 테스트 추가 및 회귀 검증

## 7. 테스트 계획

## 7.1 단위 테스트

- scope 서버 필터링 동작
- scope 툴 필터링 동작
- execution 차단 동작

## 7.2 통합 테스트

- Product/Reservation/Search 각 컨트롤러에서 허용 툴만 실행되는지 검증
- `stream`/`chat` 응답 포맷 일관성 검증
- `clear`/`status` 정상 동작 검증

## 7.3 회귀 테스트

- 기존 `HttpChatController` API 시나리오 유지 확인

## 8. SOLID/유지보수 기준

- SRP: Controller는 HTTP, Service는 유스케이스, Planner/Executor는 도메인 책임 분리
- OCP: 신규 도메인 추가 시 scope + 컨트롤러 추가만으로 확장
- DIP: 기존 인터페이스(`PlanningService`, `ToolExecutionService`) 중심 의존 유지
- DRY: stream/chat/clear/status 공통 로직 통합
- 방어적 설계: 계획/실행 2단계 검증으로 안전성 확보

## 9. 리스크 및 확인 필요사항

## 9.1 MCP 전송 방식 확인 필요

요구사항 기준으로 `sale-product`, `reservation`는 `SSE` 방식(`host: http://10.225.18.50:8080`)으로 확정한다.  
현재 코드베이스는 `StdioMcpClient` 중심이므로 `SSE MCP 클라이언트` 구현이 필요하다.

1. `McpClient` 구현체 추가: `SseMcpClient`(가칭)
2. `McpClientFactory`가 `transport` 값(`stdio`/`sse`)으로 구현체 선택
3. 연결 끊김/서버 재기동 대응 재연결 정책 포함

## 9.2 성능/캐시

- 서버 재시작/재배포를 고려해 "재연결 우선" 정책으로 동작해야 함
- 도구 스키마 캐시는 조회 비용 절감을 위한 보조 수단으로만 사용
- 캐시 TTL, 실패 시 폴백, 재연결 성공 시 즉시 갱신 정책 명시 필요

## 9.3 운영 로그

- 차단 로그(서버/툴 불일치) 관측 지표화 필요

## 10. 산출물

- 신규 컨트롤러 3종
- 공통 scoped 서비스 계층
- scope 모델/상태 전달 코드
- planning/execution 필터링 로직
- 관련 테스트 코드
- 본 문서(리팩토링 기준선)
