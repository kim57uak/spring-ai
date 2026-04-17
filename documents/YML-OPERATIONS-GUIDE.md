# YML 운영 설정 가이드

## 1. 목적과 범위

이 문서는 아래 4개 설정 파일의 운영 관점 설명서다.

- `src/main/resources/application.yml`
- `src/main/resources/a2a-supervisor.yml`
- `src/main/resources/a2a-supervisor-hitl.yml`
- `src/main/resources/mcp.yml`

설명 범위:

- 설정 키의 의미
- 현재 저장소 기준 값과 코드 기본값 차이
- 설정 조합별 런타임 결과

---

## 2. 설정 로딩 순서와 우선순위

`application.yml`은 아래 파일을 `optional:classpath:`로 import 한다.

- `systemPrompt.yml`
- `mcp.yml`
- `a2a-supervisor.yml`
- `a2a-supervisor-hitl.yml`
- `supervisoSystemPrompt.yml`

운영 포인트:

- import 대상 파일이 없어도 애플리케이션은 기동된다.
- 동일 키 충돌 시 Spring Boot 표준 우선순위가 적용된다.
- `${ENV:default}`는 환경변수가 없으면 default를 사용한다.

---

## 3. `application.yml` 운영 기준

### 3.1 핵심 키 설명

| 키 | 현재 값 | 코드 기본값/동작 | 운영 의미 |
|---|---|---|---|
| `server.port` | `8082` | Spring 기본 `8080` | HTTP 포트 |
| `app.redis.enabled` | `true` | InMemory 계열 빈은 `false` 또는 미설정일 때 활성 | `true`면 Redis 저장소 사용, `false`면 InMemory 저장소 사용 |
| `spring.data.redis.host` | `${REDIS_HOST:localhost}` | `localhost` | Redis 연결 대상 |
| `spring.data.redis.port` | `${REDIS_PORT:6379}` | `6379` | Redis 포트 |
| `spring.data.redis.timeout` | `2s` | Spring Data Redis 기본 정책 | Redis IO 타임아웃 |
| `spring.ai.openai.api-key` | `${http-llm.openai.api-key}` | 없음 | Spring AI OpenAI 브리지 |
| `http-llm.openai.api-key` | `${OPENAI_API_KEY:}` | 빈 문자열 | OpenAI 키 |
| `http-llm.gemini.api-key` | `${GEMINI_API_KEY}` | 환경변수 의존 | Gemini 키 |
| `http-llm.gemini-lite.api-key` | `${GEMINI_API_KEY}` | 환경변수 의존 | Gemini Lite 키 |
| `http-llm.mistral.api-key` | `${MISTRAL_API_KEY}` | 환경변수 의존 | Mistral 키 |
| `llm.rate-limit.enabled` | `true` | `true` | LLM 요청 간격/재시도 정책 활성화 |
| `llm.rate-limit.min-interval-ms` | `1000` | `1000` | 공급사 호출 최소 간격 |
| `llm.rate-limit.max-retries` | `3` | `3` | LLM 호출 재시도 횟수 |
| `llm.rate-limit.initial-backoff-ms` | `1000` | `1000` | LLM 재시도 초기 backoff |
| `llm.rate-limit.max-backoff-ms` | `15000` | `15000` | LLM 재시도 backoff 상한 |

### 3.2 운영 케이스

| 케이스 | 설정 | 결과 |
|---|---|---|
| Redis 미사용 단일 인스턴스 | `app.redis.enabled=false` | task/review/swarm/checkpoint가 InMemory 구현으로 동작 |
| Redis 사용 다중 인스턴스 | `app.redis.enabled=true` + Redis 정상 | Redis 저장소 기반으로 상태 공유 |
| Redis 사용 + Redis 장애 | `app.redis.enabled=true` + Redis 연결 실패 | Redis 연산 시점에 예외 가능 |
| 공급사 API 키 누락 | `http-llm.*.api-key` 미설정 | planning/compose/HITL 평가 일부가 실패할 수 있음 |
| 레이트리밋 해제 | `llm.rate-limit.enabled=false` | burst 트래픽 시 429 위험 증가 |

---

## 4. `a2a-supervisor.yml` 운영 기준

### 4.1 핵심 키 설명

| 키 | 현재 값 | 코드 기본값 | 운영 의미 |
|---|---|---|---|
| `host.a2a.routing.{agent}.endpoint` | agent별 URL | 없음 | downstream A2A endpoint. 미설정 시 라우팅 실패 |
| `host.a2a.routing.{agent}.method` | `message/send` | `message/send` | 기본 downstream 메서드 |
| `host.a2a.routing.{agent}.timeout-ms` | `120000` | `10000` | agent별 호출 타임아웃 |
| `host.a2a.retry.max-retries` | `0` | `1` | 추가 재시도 횟수. 총 시도는 `N+1` |
| `host.a2a.retry.initial-backoff-ms` | `500` | `500` | 재시도 초기 backoff |
| `host.a2a.retry.max-backoff-ms` | `3000` | `3000` | 재시도 backoff 상한 |
| `host.a2a.circuit-breaker.enabled` | `true` | `true` | circuit breaker 활성화 |
| `host.a2a.circuit-breaker.failure-threshold` | `2` | `3` | 연속 실패 임계치 |
| `host.a2a.circuit-breaker.open-duration-ms` | `30000` | `30000` | open 유지 시간 |
| `host.a2a.execution.max-concurrency` | `2` | `1` | invoke 배치 동시 실행 개수 |
| `host.a2a.history.max-turns` | `5` | `5` | planning/compose에 주입할 최근 대화 턴 수 |
| `host.a2a.a2ui.enabled` | `true` | `false` | supervisor A2UI compose/build 활성화 |
| `host.a2a.handoff.enabled` | `true` | `false` | handoff 적용 스위치 |
| `host.a2a.handoff.max-hops` | `3` | `3` | handoff 체인 최대 깊이 |
| `host.a2a.handoff.block-same-agent-within-steps` | `2` | `2` | 최근 경로 내 동일 agent 재진입 차단 |
| `host.a2a.handoff.max-per-minute` | `10` | `10` | 분당 accepted handoff 허용량 |
| `host.a2a.handoff.allow-methods` | 4개 메서드 | 모든 supervisor method | handoff 허용 메서드 화이트리스트 |
| `host.a2a.stream.timeout-ms` | `120000` | `30000` | supervisor SSE 전체 타임아웃 |

### 4.2 운영 케이스

| 케이스 | 설정 | 결과 |
|---|---|---|
| 다운스트림 1회만 시도 | `max-retries=0` | 각 라우팅 plan당 1회 호출 |
| 다운스트림 재시도 허용 | `max-retries=1` | 최대 2회 시도 |
| 동시 호출 최적화 | `max-concurrency=2` | invoke 배치에서 최대 2개 plan 병렬 처리 |
| 순차 안정 모드 | `max-concurrency=1` | 순차 호출 |
| A2UI 비활성 | `a2ui.enabled=false` | 일반 텍스트 compose만 수행 |
| handoff 비활성 | `handoff.enabled=false` | directive는 기록만 하고 기존 plan 유지 |
| handoff 루프 억제 | `max-hops=3` + `block-same-agent-within-steps=2` | hop 초과/최근 중복 agent handoff 거부 |
| circuit open 상태 | `enabled=true` + 임계치 도달 | 해당 agent 호출 즉시 `CIRCUIT_OPEN` 실패 |
| stream 장기 처리 | `stream.timeout-ms` 확장 | streaming 응답 타임아웃 완화 |

### 4.3 `a2a-supervisor-hitl.yml` 운영 포인트

- `host.a2a.hitl.reason-messages`는 reason code별 사용자 노출 문구다.
- `default`는 매핑되지 않은 신규 reason code fallback이다.
- 현재 supervisor는 `tasks/review/get`, `tasks/review/decide`를 지원한다.
- 현재 review 결정 타입은 `APPROVE`, `CANCEL`만 허용한다.

---

## 5. `mcp.yml` 운영 기준

### 5.1 `mcp.servers` 키 설명

| 키 | 의미 | 운영 결과 |
|---|---|---|
| `mcp.servers.{name}.transport` | `stdio` 또는 `sse` | `stdio`는 로컬 프로세스 실행, `sse`는 HTTP/SSE 연결 |
| `mcp.servers.{name}.command` | stdio 실행 바이너리 | `stdio`일 때 필수 수준 |
| `mcp.servers.{name}.args` | stdio 실행 인자 | 스크립트 경로/인자 전달 |
| `mcp.servers.{name}.env` | 프로세스/호출 환경변수 | 보안 검증 대상 |
| `mcp.servers.{name}.host` | SSE 호스트 | `sse`일 때 필수 |
| `mcp.servers.{name}.endpoint` | SSE 엔드포인트 경로 | 현재 설정은 `/sse` 사용 |
| `mcp.servers.{name}.timeout-ms` | MCP 요청 타임아웃 | stdio/SSE 요청 timeout 기준 |
| `mcp.servers.{name}.allow-tools` | 서버 레벨 허용 도구 | 비어 있으면 전체 허용 |
| `mcp.servers.{name}.tool-policies.{tool}.operation` | `query` 또는 `mutation` | mutation이면 보수 정책 적용 |
| `mcp.servers.{name}.tool-policies.{tool}.retryable` | 재시도 허용 여부 | `false`면 자동 재호출 안 함 |
| `mcp.servers.{name}.tool-policies.{tool}.max-calls-per-request` | 요청당 최대 호출 횟수 | 초과 시 skip |
| `mcp.servers.{name}.tool-policies.{tool}.require-idempotency-key` | idempotency key 강제 여부 | `true`면 자동 주입 |

### 5.2 `agent.scopes` 키 설명

| 키 | 의미 | 운영 결과 |
|---|---|---|
| `agent.scopes.{scope}.allowed-servers` | scope별 허용 서버 | scope 외 서버는 실행 차단 |
| `agent.scopes.{scope}.allowed-tools-by-server` | 서버별 허용 도구 | 비어 있으면 해당 서버 모든 도구 허용 |

---

## 6. 현재 저장소 / TTL 운영 포인트

- `app.redis.enabled=true`면 supervisor task/review/swarm/sub-agent task/idempotency가 Redis 기반으로 동작한다.
- `app.redis.enabled=false`면 InMemory 구현이 활성화된다.
- supervisor Redis task 저장소는 `supervisor:task:{taskId}`, `supervisor:tasks:index` 구조를 사용한다.
- review는 `supervisor:review:{taskId}` 구조를 사용한다.
- 공통 TTL은 현재 30분 정책을 따른다.

---

## 7. 운영 권장값

| 환경 | 권장값 |
|---|---|
| 로컬 개발 | `app.redis.enabled=false`, `host.a2a.execution.max-concurrency=1`, `host.a2a.retry.max-retries=0`, `host.a2a.a2ui.enabled=true` |
| 스테이징 | `app.redis.enabled=true`, `host.a2a.handoff.enabled=true`, `host.a2a.circuit-breaker.enabled=true` |
| 운영 | Redis 필수, `max-retries`는 0~1 범위에서 시작, handoff on 시 `max-hops<=3` 유지 |

---

## 8. 운영 체크리스트

- 배포 전 환경변수 확인: `OPENAI_API_KEY`, `GEMINI_API_KEY`, `MISTRAL_API_KEY`, `REDIS_HOST`, `REDIS_PORT`
- `a2a-supervisor.yml`의 실제 라우팅 endpoint 확인
- `mcp.servers`의 stdio `command/args` 경로 존재 여부 확인
- `sse` 서버는 `host + endpoint` 헬스체크 확인
- `agent.scopes` 변경 시 허용 서버/도구 누락 여부 점검
- `max-retries` 변경 시 실제 총 시도 수(`N+1`) 기준으로 부하 영향 확인
- handoff 활성화 시 `max-hops`, `max-per-minute`, 중복경로 차단 값 동시 검토
- A2UI 사용 시 `host.a2a.a2ui.enabled=true`와 product 결과 흐름을 함께 검증
