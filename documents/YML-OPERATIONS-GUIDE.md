# YML 운영 설정 가이드

## 1. 목적과 범위

이 문서는 아래 3개 설정 파일의 운영 관점 설명서다.

- `src/main/resources/application.yml`
- `src/main/resources/a2a-supervisor.yml`
- `src/main/resources/mcp.yml`

설명 범위는 다음과 같다.

- 설정 키의 의미
- 현재 저장소 기준 값과 코드 기본값 차이
- 설정 조합별 런타임 결과(성공/차단/폴백/실패)

---

## 2. 설정 로딩 순서와 우선순위

`application.yml`에서 다음 파일을 `optional:classpath:`로 import 한다.

- `systemPrompt.yml`
- `mcp.yml`
- `a2a-supervisor.yml`
- `supervisoSystemPrompt.yml`

운영 포인트:

- import 대상 파일이 없어도 애플리케이션은 기동된다(`optional`).
- 동일 키가 여러 곳에 있으면 Spring Boot 표준 우선순위(환경변수/외부설정 > 클래스패스 파일)가 적용된다.
- 환경변수 치환 `${ENV:default}`는 `ENV`가 비어 있으면 default를 사용한다.

---

## 3. `application.yml` 운영 기준

### 3.1 핵심 키 설명

| 키 | 현재 값 | 코드 기본값/동작 | 운영 의미 |
|---|---|---|---|
| `server.port` | `8082` | Spring 기본 `8080` | HTTP 포트 |
| `app.redis.enabled` | `true` | 미설정 시 `false` 경로 빈이 활성화됨(`matchIfMissing=true`) | `true`면 Redis 저장소 사용, `false`면 InMemory 저장소 사용 |
| `spring.data.redis.host` | `${REDIS_HOST:localhost}` | `localhost` | Redis 연결 대상 |
| `spring.data.redis.port` | `${REDIS_PORT:6379}` | `6379` | Redis 포트 |
| `spring.data.redis.timeout` | `2s` | Spring Data Redis 기본 타임아웃 정책 | Redis IO 타임아웃 |
| `spring.ai.openai.api-key` | `${http-llm.openai.api-key}` | 없음 | Spring AI OpenAI API 키 브리지 |
| `http-llm.openai.api-key` | `${OPENAI_API_KEY:}` | 빈 문자열 | OpenAI 모델 키 |
| `http-llm.gemini.api-key` | `${GEMINI_API_KEY}` | 치환 실패 시 `${...}` 그대로 남음 | Gemini 모델 키 |
| `http-llm.mistral.api-key` | `${MISTRAL_API_KEY}` | 치환 실패 시 `${...}` 그대로 남음 | Mistral 모델 키 |
| `llm.rate-limit.enabled` | `true` | `true` | LLM 호출 간 최소 간격/재시도 정책 활성화 |
| `llm.rate-limit.min-interval-ms` | `1000` | `1000` | 공급사 호출 최소 간격 |
| `llm.rate-limit.max-retries` | `3` | `3` | LLM 호출 재시도 횟수 |

### 3.2 운영 케이스

| 케이스 | 설정 | 결과 |
|---|---|---|
| Redis 미사용 단일 인스턴스 | `app.redis.enabled=false` | Task/Review/Swarm 저장소가 InMemory 구현으로 동작. 재기동 시 상태 유실 |
| Redis 사용 다중 인스턴스 | `app.redis.enabled=true` + Redis 정상 | Redis 저장소 기반으로 세션/상태 공유 |
| Redis 사용 + Redis 장애 | `app.redis.enabled=true` + Redis 연결 실패 | Redis 연산 시점에 예외 가능(운영상 health-check/재시도/모니터링 필요) |
| 공급사 API 키 누락 | `http-llm.*.api-key`가 빈값/placeholder | Chat 서비스 빈 초기화 시 검증 예외로 기동 실패 가능 |
| 레이트리밋 해제 | `llm.rate-limit.enabled=false` | 호출 간격 제어/재시도 정책 완화, burst 트래픽 시 429 위험 증가 |

---

## 4. `a2a-supervisor.yml` 운영 기준

### 4.1 핵심 키 설명

| 키 | 현재 값 | 코드 기본값 | 운영 의미 |
|---|---|---|---|
| `host.a2a.routing.{agent}.endpoint` | agent별 URL | 없음(미설정 시 라우팅 실패) | Supervisor가 하위 A2A agent 호출할 대상 |
| `host.a2a.routing.{agent}.method` | `message/send` | `message/send` | 기본 JSON-RPC 메서드 |
| `host.a2a.routing.{agent}.timeout-ms` | `120000` | `10000` | agent별 timeout |
| `host.a2a.retry.max-retries` | `0` | `1` | 재시도 횟수. 실제 최대 시도 수는 `max-retries + 1` |
| `host.a2a.retry.initial-backoff-ms` | `500` | `500` | 재시도 초기 backoff |
| `host.a2a.retry.max-backoff-ms` | `3000` | `3000` | 재시도 backoff 상한 |
| `host.a2a.circuit-breaker.enabled` | `true` | `true` | 연속 실패 agent 차단 활성화 |
| `host.a2a.circuit-breaker.failure-threshold` | `3` | `3` | 연속 실패 임계치 |
| `host.a2a.circuit-breaker.open-duration-ms` | `30000` | `30000` | 회로 open 유지 시간 |
| `host.a2a.execution.max-concurrency` | `2` | `1` | 그래프 invoke 배치 병렬 개수 |
| `host.a2a.handoff.enabled` | `true` | `false` | handoff 기능 on/off |
| `host.a2a.handoff.max-hops` | `3` | `3` | handoff 체인 깊이 제한 |
| `host.a2a.handoff.block-same-agent-within-steps` | `2` | `2` | 최근 경로 내 동일 agent 재진입 차단 |
| `host.a2a.handoff.max-per-minute` | `10` | `10` | 분당 handoff 허용량 |
| `host.a2a.handoff.allow-methods` | 4개 메서드 | 모든 supervisor method | handoff 허용 메서드 화이트리스트 |
| `host.a2a.stream.timeout-ms` | `120000` | `30000` | supervisor `message/stream` SSE timeout |

### 4.2 운영 케이스

| 케이스 | 설정 | 결과 |
|---|---|---|
| 다운스트림 1회만 시도 | `max-retries=0` | 각 라우팅 plan당 1회 호출 후 실패 처리 |
| 다운스트림 재시도 허용 | `max-retries=1` | 최대 2회 시도. 메서드 fallback(`-32601`)은 별도 경로로 추가 시도 가능 |
| 동시 호출 최적화 | `max-concurrency=2` | invoke 배치에서 최대 2개 plan 병렬 처리 |
| 순차 안정 모드 | `max-concurrency=1` | 순차 호출, 외부 부하 낮지만 응답 지연 증가 |
| handoff 비활성 | `handoff.enabled=false` | handoff directive는 적용되지 않고 기존 plan 유지 |
| handoff 루프 억제 | `max-hops=3` + `block-same-agent-within-steps=2` | hop 초과/최근 중복 agent handoff 거부 |
| circuit open 상태 | `enabled=true` + 임계치 도달 | 해당 agent 호출 즉시 실패(`CIRCUIT_OPEN`)로 단락 |
| stream 장기 처리 | `stream.timeout-ms` 확장 | streaming 응답 타임아웃 완화 |

---

## 5. `mcp.yml` 운영 기준

### 5.1 `mcp.servers` 키 설명

| 키 | 의미 | 운영 결과 |
|---|---|---|
| `mcp.servers.{name}.transport` | `stdio` 또는 `sse` | `stdio`는 로컬 프로세스 실행, `sse`는 HTTP/SSE 연결 |
| `mcp.servers.{name}.command` | stdio 실행 바이너리 | `stdio`일 때 필수 수준. 화이트리스트/보안검증 대상 |
| `mcp.servers.{name}.args` | stdio 실행 인자 | 스크립트 경로/인자 전달 |
| `mcp.servers.{name}.env` | 프로세스/호출 환경변수 | 키 포맷/값 보안 검증 적용 |
| `mcp.servers.{name}.host` | SSE 호스트 | `sse`일 때 필수. 비어 있으면 초기화 실패 |
| `mcp.servers.{name}.endpoint` | SSE 엔드포인트 경로 | 기본 `/mcp`, 현재 설정은 `/sse` 사용 |
| `mcp.servers.{name}.timeout-ms` | MCP 요청 타임아웃 | stdio 응답 대기/SSE 요청 timeout 기준 |
| `mcp.servers.{name}.allow-tools` | 서버 레벨 허용 도구 | 비어 있으면 전체 허용, 값이 있으면 화이트리스트 제한 |
| `mcp.servers.{name}.tool-policies.{tool}.operation` | `query` 또는 `mutation` | mutation이면 안전 가드(호출 횟수/재시도/idempotency) 정책 적용 |
| `mcp.servers.{name}.tool-policies.{tool}.retryable` | 재시도 허용 여부 | `false`면 `[ERROR][REQUEST_FAILED]` 응답이어도 재호출하지 않음 |
| `mcp.servers.{name}.tool-policies.{tool}.max-calls-per-request` | 요청당 최대 호출 횟수 | 횟수 초과 시 도구 실행을 정책적으로 skip |
| `mcp.servers.{name}.tool-policies.{tool}.require-idempotency-key` | idempotency key 강제 주입 여부 | `true`면 인자에 `idempotencyKey` 자동 주입(없을 때만) |
| `mcp.servers.{name}.capabilities` | 메타 정보 | 현재 코드에서 실행 차단 규칙으로 직접 사용되지는 않음 |

### 5.2 `agent.scopes` 키 설명

| 키 | 의미 | 운영 결과 |
|---|---|---|
| `agent.scopes.{scope}.allowed-servers` | scope별 허용 서버 | scope 외 서버는 실행 차단 |
| `agent.scopes.{scope}.allowed-tools-by-server` | 서버별 허용 도구 | 비어 있으면 해당 서버 모든 도구 허용 |

### 5.3 운영 케이스

| 케이스 | 설정 | 결과 |
|---|---|---|
| stdio 서버 정상 | `transport=stdio` + `command/args` 유효 | ProcessManager가 프로세스 생성 후 재사용 |
| stdio command 오류 | `command` 누락/비허용 실행파일/위험문자 포함 | 실행 검증에서 예외 발생, 도구 호출 실패 |
| sse 서버 정상 | `transport=sse` + `host` 유효 | SseMcpClient 연결 및 tools/list 조회 |
| sse host 누락 | `transport=sse` + `host` 없음 | 클라이언트 생성 시 예외(`SSE MCP host is required`) |
| allow-tools 비어 있음 | `allow-tools: []` | 서버의 모든 도구 후보 사용 가능 |
| allow-tools 지정 | 예: `["createReservation"]` | 지정 도구만 실행/선택 가능 |
| mutation 도구 보호 | `tool-policies.createReservation.operation=mutation`, `max-calls-per-request=1`, `retryable=false` | 요청 1건에서 생성 도구는 1회만 실행, 자동 재시도 차단 |
| query 도구 반복 허용 | `tool-policies.getSaleProductDetails.operation=query`, `max-calls-per-request=4`, `retryable=true` | 동일 요청에서 조회 도구 다회 호출 허용(상한 내) |
| 미정의 mutation 추정 보호 | `tool-policies` 미설정 + 도구명이 `create/update/delete/...` 패턴 | 코드에서 mutation으로 추정하여 보수 가드(1회/무재시도/idempotency) 적용 |
| scope로 서버 차단 | `allowed-servers`에 서버 없음 | 해당 서버 tool 실행 즉시 차단 |
| scope로 도구 차단 | `allowed-tools-by-server`에서 미허용 | 서버는 허용되어도 tool 단계에서 차단 |

---

## 6. 운영 권장값(초기안)

| 환경 | 권장값 |
|---|---|
| 로컬 개발 | `app.redis.enabled=false`, `host.a2a.execution.max-concurrency=1`, `host.a2a.retry.max-retries=0` |
| 스테이징 | `app.redis.enabled=true`, `host.a2a.handoff.enabled=true`, `host.a2a.circuit-breaker.enabled=true` |
| 운영 | Redis 필수, `max-retries`는 0~1 범위에서 시작, handoff on 시 `max-hops<=3` 유지 |

---

## 7. 운영 체크리스트

- 배포 전 환경변수 확인: `OPENAI_API_KEY`, `GEMINI_API_KEY`, `MISTRAL_API_KEY`, `REDIS_HOST`, `REDIS_PORT`
- `mcp.servers`의 stdio `command/args` 경로 존재 여부 확인
- `sse` 서버는 `host + endpoint` 헬스체크 확인
- `agent.scopes` 변경 시 허용 서버/도구 누락 여부 점검
- `max-retries` 변경 시 실제 총 시도 수(`N+1`) 기준으로 부하 영향 확인
- handoff 활성화 시 `max-hops`, `max-per-minute`, 중복경로 차단 값 동시 검토
