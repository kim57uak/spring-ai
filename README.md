# spring-ai

Spring Boot 기반 멀티 에이전트 프로젝트입니다.  
한 애플리케이션 안에 `downstream sub-agent(product/reservation/search)`와 `supervisor agent`가 함께 동작하며, A2A(JSON-RPC/SSE)로 연결됩니다.

## 1. 프로젝트 구성

- Sub-agent 패키지: `com.example.springai`
- Supervisor 패키지: `com.example.springsupervisorai`
- 메인 앱: `SpringAiApplication`에서 두 패키지를 함께 스캔
- 기본 포트: `8082`

핵심 경로:
- Base API: `/api/*`
- Sub-agent A2A:
  - `/a2a/product`
  - `/a2a/reservation`
  - `/a2a/search`
- Supervisor A2A:
  - `/a2a/supervisor`
  - `/a2a/supervisor/stream` (alias)
- Agent Card:
  - `/.well-known/agent.json`
  - `/a2a/{scope}/.well-known/agent.json`

## 2. 기술 스펙

- Java 21
- Spring Boot `3.4.3`
- Spring AI BOM `1.0.3`
- LangGraph4j BOM `1.8.10`
- A2A Java SDK BOM `1.0.0.Alpha4`
- Redis (대화/체크포인트 저장)
- MCP 연동 지원 (`sse`, `stdio`)

주요 설정 파일:
- [application.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/application.yml)
- [mcp.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/mcp.yml)
- [a2a-supervisor.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/a2a-supervisor.yml)
- [a2a-supervisor-hitl.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/a2a-supervisor-hitl.yml)
- [systemPrompt.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/systemPrompt.yml)
- [supervisoSystemPrompt.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/supervisoSystemPrompt.yml)

## 3. 실행 방법

환경 변수(.env 권장):
- `OPENAI_API_KEY`
- `GEMINI_API_KEY`
- `MISTRAL_API_KEY`
- `PERPLEXITY_API_KEY`
- `REDIS_HOST`, `REDIS_PORT`

실행:

```bash
./gradlew bootRun
```

테스트:

```bash
./gradlew test
```

## 4. Supervisor/Downstream 연결 관리

Supervisor가 어떤 downstream을 호출할지 `a2a-supervisor.yml`의 `host.a2a.routing`으로 관리합니다.

### 4.1 라우팅 설정 예시 (a2a-supervisor.yml)

```yaml
host:
  a2a:
    routing:
      # Product Agent 라우팅
      product:
        endpoint: ${SUPERVISOR_PRODUCT_A2A_ENDPOINT:http://localhost:8082/a2a/product}
        method: message/send
        timeout-ms: 120000

      # Reservation Agent 라우팅
      reservation:
        endpoint: ${SUPERVISOR_RESERVATION_A2A_ENDPOINT:http://localhost:8082/a2a/reservation}
        method: message/send
        timeout-ms: 120000

      # Search Agent 라우팅
      search:
        endpoint: ${SUPERVISOR_SEARCH_A2A_ENDPOINT:http://localhost:8082/a2a/search}
        method: message/send
        timeout-ms: 120000

      # 외부 Agent 예시
      mygenie:
        endpoint: ${MYGENIE_A2A_ENDPOINT:http://localhost:8001/a2a}
        method: message/send
        timeout-ms: 120000

    # 재시도 정책
    retry:
      max-retries: 1
      initial-backoff-ms: 500
      max-backoff-ms: 3000

    # 회로 차단기 정책
    circuit-breaker:
      enabled: true
      failure-threshold: 3
      open-duration-ms: 30000

    # 프롬프트 히스토리(최근 대화 턴 수)
    history:
      max-turns: 5

    # 스트림 타임아웃
    stream:
      timeout-ms: 120000
```

### 4.2 환경 변수 오버라이드

운영에서 외부 downstream으로 분리할 경우 아래 ENV로 endpoint를 교체합니다:

```bash
# 개발 환경 (동일 서버)
export SUPERVISOR_PRODUCT_A2A_ENDPOINT=http://localhost:8082/a2a/product
export SUPERVISOR_RESERVATION_A2A_ENDPOINT=http://localhost:8082/a2a/reservation
export SUPERVISOR_SEARCH_A2A_ENDPOINT=http://localhost:8082/a2a/search

# 운영 환경 (분리된 서버)
export SUPERVISOR_PRODUCT_A2A_ENDPOINT=https://product-agent.example.com/a2a
export SUPERVISOR_RESERVATION_A2A_ENDPOINT=https://reservation-agent.example.com/a2a
export SUPERVISOR_SEARCH_A2A_ENDPOINT=https://search-agent.example.com/a2a
```

### 4.3 라우팅 키 설명

각 라우팅 키(product, reservation, search)는:
- Supervisor Planning 단계에서 `agentKey`로 사용됩니다
- `supervisoSystemPrompt.yml`의 Planning 프롬프트에서 `allowedAgents` 목록으로 전달됩니다
- LLM이 사용자 요청을 분석하여 적절한 agent를 선택합니다

### 4.4 허용 메서드 및 정책

- **호출 허용 메서드**: `message/send`, `message/stream`, `tasks/get`, `tasks/list`, `tasks/cancel`
- **재시도**: `host.a2a.retry.*`
- **회로 차단기**: `host.a2a.circuit-breaker.*`
- **프롬프트 히스토리 턴 수**: `host.a2a.history.max-turns` (현재 `5`, 최근 user+assistant 5턴)
- **HITL reason 문구 매핑**: `host.a2a.hitl.reason-messages` (`a2a-supervisor-hitl.yml`에서 관리)
- **스트림 타임아웃**: `host.a2a.stream.timeout-ms`

## 5. Downstream 에이전트 관리

### 5.1 활성/비활성 제어

- 실제 scope 정의: `mcp.yml > agent.scopes`
- 카드/노출 강제 제한: `agent.cards.enabled-scopes`

동작 원칙:
- `agent.cards.enabled-scopes`가 비어 있으면 `agent.scopes` 전체 활성
- 값이 있으면 해당 scope만 활성 (나머지는 카드/엔드포인트 비활성 취급)

### 5.2 신규 downstream 추가 절차

1. `mcp.yml > mcp.servers`에 서버 등록
2. `mcp.yml > agent.scopes.{newScope}`에 allowed server/tool 등록
3. Sub-agent A2A 컨트롤러/스코프 연결 추가
4. `a2a-supervisor.yml > host.a2a.routing.{newScope}` 추가
5. Agent Card 노출 정책(`agent.cards.enabled-scopes`) 점검
6. `tasks/get|list|cancel` 세션 소유권 테스트 확인

### 5.3 Timeout/완료 규약 (하위 에이전트 필수)

하위 에이전트(product/reservation/search/mygenie 포함)는 아래 규약을 반드시 지켜야 합니다.
이 규약이 깨지면 `API 서버까지는 호출되지만 상위에서 timeout` 증상이 발생합니다.

- `message/send`(unary) 응답은 무한 대기 없이 종료되어야 합니다.
- `message/stream`(SSE) 응답은 마지막에 반드시 종료 신호를 내려야 합니다.
- 종료 신호 예시: `event: done` 또는 `data: [DONE]`
- 종료 신호를 보낸 뒤 연결이 정리(complete/close)되어야 합니다.
- 실패 시에도 조용히 멈추지 말고 `[ERROR][...]` 형태의 명시적 에러 페이로드를 반환해야 합니다.
- 하위 에이전트 내부 타임아웃은 상위 타임아웃보다 짧아야 합니다.
- 재시도는 유한 횟수로 제한하고, 재시도 소진 시 즉시 실패 응답을 반환해야 합니다.
- supervisor가 전달한 `X-A2A-Session-Id` 세션은 응답 완료/timeout/cancel 시 즉시 `clear`로 종료해야 합니다.
- 세션 idle timeout이 지난 경우에도 동일 세션을 재사용하지 말고 `clear` 후 신규 세션으로 처리해야 합니다.

권장 타임아웃 계층:
- 하위 MCP/API timeout < 하위 agent 처리 timeout < supervisor route timeout < supervisor stream timeout
- 현재 기본값 예시: `ScopedAgentChatService` sync timeout `90s`, `host.a2a.routing.*.timeout-ms` `120s`, `host.a2a.stream.timeout-ms` `120s`

하위 에이전트 구현 체크리스트:
- long-running 작업은 heartbeat 또는 중간 chunk를 주기적으로 전송
- stream 경로에서 예외 발생 시에도 `done` 또는 동등한 종료 토큰 보장
- 외부 API 호출/브라우저 자동화(Puppeteer 등) 종료 후 리소스(browser/session) 정리
- 세션 기반 transport(SSE MCP) 사용 시 세션 만료/채널 종료 시 재초기화 로직 포함
- `/a2a/{scope}/clear` 엔드포인트를 제공하고, `X-A2A-Session-Id` 기준으로 해당 세션 상태를 즉시 삭제

### 5.4 Redis 영속화 항목 (클러스터 권장)

- 아래 데이터는 Redis 기반으로 동작하도록 구현되어 있습니다(활성화: `app.redis.enabled=true`).
- 공통 TTL은 30분입니다.
- 하위 A2A task: `agent:task:{taskId}`, `agent:tasks:scope:{SCOPE}`
- Supervisor A2A task: `supervisor:task:{taskId}`, `supervisor:tasks:index`
- HITL review: `supervisor:review:{taskId}`
- 하위 idempotency: `idempotency:a2a:response:{dedupeKey}`, `idempotency:a2a:lock:{dedupeKey}`
- Supervisor idempotency: `idempotency:supervisor:response:{dedupeKey}`, `idempotency:supervisor:lock:{dedupeKey}`

## 6. 개발자 가이드

- 예외 응답:
  - `/a2a/**` 경로는 JSON-RPC 에러 envelope로 반환
  - `/api/**` 경로는 일반 `ErrorResponse` 반환
- A2A idempotency:
  - `message/send`는 requestId 기준 중복 실행 방지
- 보안:
  - PromptInjectionGuard로 입력/결과 정제
  - endpoint allowlist 기반 라우팅 강제
- Task 접근 제어:
  - `scope + sessionId` 소유권 검증으로 cross-session 접근 차단

## 7. 운영 시 주의사항

- Redis 미연결 시 대화 히스토리/체크포인트 일관성이 깨질 수 있습니다.
- 클러스터 환경에서는 `app.redis.enabled=true`로 실행하고, 아래 Redis 데이터 TTL(30분)을 운영 기준으로 유지하세요.
  - `agent:task:{taskId}`, `agent:tasks:scope:{SCOPE}` (하위 A2A task)
  - `supervisor:task:{taskId}`, `supervisor:tasks:index` (supervisor task)
  - `supervisor:review:{taskId}` (HITL review)
  - `idempotency:a2a:response:{dedupeKey}`, `idempotency:a2a:lock:{dedupeKey}` (하위 idempotency)
  - `idempotency:supervisor:response:{dedupeKey}`, `idempotency:supervisor:lock:{dedupeKey}` (supervisor idempotency)
- 키 전략 주의:
  - idempotency dedupeKey는 `sessionId` 포함(세션 간 응답 오염 방지)
  - task/review는 API 계약(`tasks/get`, `tasks/review/get`)이 `taskId` 조회 기반이므로 저장 키는 `taskId` 기반
- 유지보수 규칙: Redis 키/TTL 하드코딩 금지, 각 앱 전용 상수(`com.example.springai.common.redis.*`, `com.example.springsupervisorai.common.redis.*`)를 참조하세요.
- `a2a-supervisor.yml` endpoint를 동일 서버(자기 자신)로 둘 경우, 의도하지 않은 루프/부하 구조가 생기지 않도록 라우팅 정책을 점검하세요.
- 프로파일별로 활성 scope가 다르면 Agent Card 노출 개수도 달라집니다. 운영/테스트 환경의 `agent.cards.enabled-scopes`를 반드시 분리 관리하세요.
- `application.yml`의 import 파일명은 실제 리소스명과 정확히 일치해야 합니다(현재 `supervisoSystemPrompt.yml` 사용).
- LLM API Key 누락 시 planning/compose 또는 MCP 연계 기능 일부가 실패할 수 있으므로 헬스체크와 시작 로그를 함께 확인하세요.

timeout 장애 트러블슈팅 포인트:
- `Timeout on blocking read for 120000000000 NANOSECONDS`가 보이면 하위 `message/send` 체인이 제한 시간 내 종료되지 않은 상태입니다.
- `MCP tool result ... payloadLength=...`가 찍혔는데 timeout이 나면, 하위 agent의 응답 완료 신호/연결 종료 누락 가능성이 큽니다.
- `Failed to refresh SSE MCP tools ... request timed out`가 반복되면 SSE MCP 세션 상태 불안정 가능성이 큽니다(재초기화/재연결 정책 점검).
- timeout 직후 동일 세션으로 재호출하지 말고, `clear` 호출 후 새 세션으로 재시도하세요.
- 복합 요청인데 특정 agent가 누락되면 `Swarm routing filtered ... skippedByCooldown/skippedByCircuit` 로그를 먼저 확인하세요.
- 직전 실패 직후에는 Swarm cooldown(기본 120초)으로 product/reservation/search 중 일부가 일시 제외될 수 있습니다.
- planner 출력 agentKey가 `Product`, `search-agent`처럼 변형되어도 파서가 정규화 처리하지만, 새로운 별칭이 필요하면 routing key와 프롬프트 예시를 같이 갱신하세요.

## 8. 관련 문서

- [A2A Host/Supervisor Architecture](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-host_agent-architecture)
- [A2A Sub-agent Architecture](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-sub_agent-architecture)
