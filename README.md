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

예시:
- `host.a2a.routing.product.endpoint`
- `host.a2a.routing.reservation.endpoint`
- `host.a2a.routing.search.endpoint`

운영에서 외부 downstream으로 분리할 경우 아래 ENV로 endpoint를 교체합니다.
- `SUPERVISOR_PRODUCT_A2A_ENDPOINT`
- `SUPERVISOR_RESERVATION_A2A_ENDPOINT`
- `SUPERVISOR_SEARCH_A2A_ENDPOINT`

정책:
- 호출 허용 메서드: `message/send`, `message/stream`, `tasks/get`, `tasks/list`, `tasks/cancel`
- 재시도: `host.a2a.retry.*`
- 회로 차단기: `host.a2a.circuit-breaker.*`
- Supervisor stream timeout: `host.a2a.stream.timeout-ms`

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
- `a2a-supervisor.yml` endpoint를 동일 서버(자기 자신)로 둘 경우, 의도하지 않은 루프/부하 구조가 생기지 않도록 라우팅 정책을 점검하세요.
- 프로파일별로 활성 scope가 다르면 Agent Card 노출 개수도 달라집니다. 운영/테스트 환경의 `agent.cards.enabled-scopes`를 반드시 분리 관리하세요.
- `application.yml`의 import 파일명은 실제 리소스명과 정확히 일치해야 합니다(현재 `supervisoSystemPrompt.yml` 사용).
- LLM API Key 누락 시 planning/compose 또는 MCP 연계 기능 일부가 실패할 수 있으므로 헬스체크와 시작 로그를 함께 확인하세요.

## 8. 관련 문서

- [A2A Host/Supervisor Architecture](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-host_agent-architecture)
- [A2A Sub-agent Architecture](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-sub_agent-architecture)
