# spring-ai

Spring Boot 기반 멀티 에이전트 프로젝트입니다.  
한 애플리케이션 안에 `downstream sub-agent(product/reservation/search)`와 `supervisor agent`가 함께 동작하며, A2A(JSON-RPC/SSE)로 연결됩니다.

## 1. 프로젝트 구성

- Sub-agent 패키지: `com.example.springai`
- Supervisor 패키지: `com.example.springsupervisorai`
- 메인 앱: `SpringAiApplication`
- 기본 포트: `8082`

핵심 경로:
- Base API: `/api/*`
- Sub-agent A2A:
  - `/a2a/product`
  - `/a2a/reservation`
  - `/a2a/search`
- Supervisor A2A:
  - `/a2a/supervisor`
  - `/a2a/supervisor/stream` (stream alias)
  - 지원 메서드:
    - `SendMessage`, `message/send`
    - `SendStreamingMessage`, `message/stream`
    - `GetTask`, `tasks/get`
    - `ListTasks`, `tasks/list`
    - `CancelTask`, `tasks/cancel`
    - `GetTaskReview`, `tasks/review/get`
    - `DecideTaskReview`, `tasks/review/decide`
- Agent Card:
  - `/.well-known/agent.json`
  - `/a2a/{scope}/.well-known/agent.json`

## 2. 기술 스펙

- Java 21
- Spring Boot `3.4.3`
- Spring AI BOM `1.0.3`
- LangGraph4j BOM `1.8.10`
- Redis 또는 InMemory 저장소
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
- `REDIS_HOST`, `REDIS_PORT`

실행:

```bash
./gradlew bootRun
```

테스트:

```bash
./gradlew test
```

## 4. 현재 Supervisor 동작 요약

현재 supervisor는 아래 순서로 동작합니다.

1. `SupervisorA2AController`가 JSON-RPC/SSE 요청과 params를 검증
2. `SupervisorAgentService`가 HITL 정책을 먼저 평가
3. review 필요 시 task를 `WAITING_REVIEW`로 만들고 `tasks/review/*`로 승인 대기
4. review 불필요 시 `SupervisorAgentOrchestrator`가 LangGraph 실행
5. graph 내부에서 `plan -> select -> invoke -> handoff_evaluate -> handoff_apply -> merge -> compose`
6. compose 단계에서 일반 텍스트 또는 A2UI payload를 생성

중요:
- HITL은 graph 내부 대기 노드가 아니라 service 레벨 사전 게이트입니다.
- handoff는 구현되어 있으며 `host.a2a.handoff.enabled`로 제어됩니다.
- A2UI는 구현되어 있으며 `host.a2a.a2ui.enabled=true`일 때만 시도됩니다.

## 5. Supervisor/Downstream 연결 관리

Supervisor가 어떤 downstream을 호출할지 `a2a-supervisor.yml`의 `host.a2a.routing`으로 관리합니다.

### 5.1 라우팅 설정 예시

```yaml
host:
  a2a:
    routing:
      product:
        endpoint: ${SUPERVISOR_PRODUCT_A2A_ENDPOINT:http://localhost:8082/a2a/product}
        method: message/send
        timeout-ms: 120000
      reservation:
        endpoint: ${SUPERVISOR_RESERVATION_A2A_ENDPOINT:http://localhost:8082/a2a/reservation}
        method: message/send
        timeout-ms: 120000
      search:
        endpoint: ${SUPERVISOR_SEARCH_A2A_ENDPOINT:http://localhost:8082/a2a/search}
        method: message/send
        timeout-ms: 120000

    retry:
      max-retries: 0
      initial-backoff-ms: 500
      max-backoff-ms: 3000

    circuit-breaker:
      enabled: true
      failure-threshold: 2
      open-duration-ms: 30000

    execution:
      max-concurrency: 2

    history:
      max-turns: 5

    a2ui:
      enabled: true

    handoff:
      enabled: true
      max-hops: 3
      block-same-agent-within-steps: 2
      max-per-minute: 10

    stream:
      timeout-ms: 120000
```

### 5.2 환경 변수 오버라이드

```bash
export SUPERVISOR_PRODUCT_A2A_ENDPOINT=http://localhost:8082/a2a/product
export SUPERVISOR_RESERVATION_A2A_ENDPOINT=http://localhost:8082/a2a/reservation
export SUPERVISOR_SEARCH_A2A_ENDPOINT=http://localhost:8082/a2a/search
```

운영에서 외부 agent로 분리할 경우 위 endpoint만 교체하면 됩니다.

### 5.3 라우팅 키 설명

각 라우팅 키(`product`, `reservation`, `search`)는:
- planning 결과의 `agentKey`
- supervisor prompt의 `allowedAgents`
- `A2AClientRegistry` route key
로 함께 사용됩니다.

### 5.4 현재 운영 정책

- retry: `host.a2a.retry.*`
- circuit breaker: `host.a2a.circuit-breaker.*`
- execution batch: `host.a2a.execution.max-concurrency`
- history turns: `host.a2a.history.max-turns`
- A2UI on/off: `host.a2a.a2ui.enabled`
- handoff on/off 및 제약: `host.a2a.handoff.*`
- HITL reason 문구 매핑: `host.a2a.hitl.reason-messages`
- stream timeout: `host.a2a.stream.timeout-ms`

## 6. HITL / Review API

현재 supervisor는 review 대기 상태를 지원합니다.

- review 조회:
  - `GetTaskReview`
  - `tasks/review/get`
- review 결정:
  - `DecideTaskReview`
  - `tasks/review/decide`

현재 지원 결정 타입:
- `APPROVE`
- `CANCEL`

`APPROVE` 시 대기 task를 다시 `RUNNING`으로 전이하고 오케스트레이션을 실행합니다.  
`CANCEL` 시 task를 취소 상태로 종료합니다.

## 7. A2UI

현재 A2UI는 supervisor에 구현되어 있습니다.

- 설정: `host.a2a.a2ui.enabled=true`
- compose 단계에서만 시도
- 현재 product 결과가 있을 때만 실질적으로 동작
- SSE에서 일반 텍스트는 `chunk`, A2UI payload는 `a2ui` 이벤트로 전달
- `submit_reservation` action은 다시 supervisor 자연어 요청으로 정규화됩니다

## 8. Downstream 에이전트 관리

### 8.1 활성/비활성 제어

- 실제 scope 정의: `mcp.yml > agent.scopes`
- 카드/노출 강제 제한: `agent.cards.enabled-scopes`

동작 원칙:
- `agent.cards.enabled-scopes`가 비어 있으면 `agent.scopes` 전체 활성
- 값이 있으면 해당 scope만 활성

### 8.2 신규 downstream 추가 절차

1. `mcp.yml > mcp.servers`에 서버 등록
2. `mcp.yml > agent.scopes.{newScope}`에 allowed server/tool 등록
3. sub-agent A2A 컨트롤러/스코프 연결 추가
4. `a2a-supervisor.yml > host.a2a.routing.{newScope}` 추가
5. agent card 노출 정책 점검
6. session ownership과 `tasks/*` 테스트 확인

## 9. 저장소와 Redis

`app.redis.enabled=true`면 Redis 구현, `false`면 InMemory 구현이 활성화됩니다.

Redis/InMemory 전환 대상:
- supervisor task store
- supervisor review store
- supervisor swarm state store
- conversation store
- graph checkpoint store
- sub-agent / supervisor idempotency 관련 저장소

운영 포인트:
- Redis가 없으면 재기동 시 상태가 유실됩니다.
- Redis가 있으면 task/review/swarm/checkpoint를 인스턴스 간 공유할 수 있습니다.

Redis 영속화 항목 예시:
- `supervisor:task:{taskId}`
- `supervisor:tasks:index`
- `supervisor:review:{taskId}`
- `idempotency:supervisor:*`
- sub-agent task / idempotency 키

## 10. 개발자 가이드

- `/a2a/**`는 JSON-RPC 에러 envelope 반환
- `/api/**`는 `ErrorResponse` 반환
- 입력 sanitizing: `PromptInjectionGuard`
- route allowlist 강제: `A2AClientRegistry`
- task/review 접근은 `sessionId` 소유권 검증 기반

## 11. 운영 시 주의사항

- `application.yml`은 실제로 아래를 import 합니다.
  - `systemPrompt.yml`
  - `mcp.yml`
  - `a2a-supervisor.yml`
  - `a2a-supervisor-hitl.yml`
  - `supervisoSystemPrompt.yml`
- `supervisoSystemPrompt.yml` 파일명은 현재 그대로 사용 중입니다.
- Redis 미연결 시 InMemory로 폴백되는 것이 아니라, `app.redis.enabled=true` 상태에서 Redis 연산 시 오류가 날 수 있으니 배포 전 연결 상태를 확인해야 합니다.
- timeout 장애가 나면 하위 agent 종료 신호 누락, SSE MCP 불안정, Swarm cooldown/circuit filtering 로그를 먼저 확인하세요.
- handoff를 켠 상태에서는 `max-hops`, `max-per-minute`, 최근 경로 차단 값을 함께 조정해야 합니다.

## 12. 관련 문서

- [A2A Host/Supervisor Architecture](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-host_agent-architecture)
- [A2A Sub-agent Architecture](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-sub_agent-architecture)
- [YML 운영 설정 가이드](/Users/dolpaks/Downloads/project/spring-ai/YML-OPERATIONS-GUIDE.md)
