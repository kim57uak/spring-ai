@/Users/dolpaks/.gemini/antigravity/ANTIGRAVITY.md

# spring-ai — 멀티 에이전트 시스템

## 아키텍처

- **두 개의 Java 패키지**가 하나의 Spring Boot 애플리케이션으로 실행됨
  - sub-agent: `com.example.springai` (MCP + A2A downstream)
  - supervisor: `com.example.springsupervisorai` (orchestrator)
  - 진입점: `SpringAiApplication`에서 `@SpringBootApplication(scanBasePackages = {"com.example.springai", "com.example.springsupervisorai"})`
- **설정 파일 Import 체인**: `application.yml` → `systemPrompt.yml`, `mcp.yml`, `a2a-supervisor.yml`, `a2a-supervisor-hitl.yml`, `supervisoSystemPrompt.yml`
- 기본 포트: `8082`

## 도메인

- 노출 HTTP 엔드포인트:
  - Base API: `/api/*`
  - A2A: `/a2a/{scope}` ({scope}: product / reservation / search / supervisor)
  - Agent Card: `/.well-known/agent.json`, `/a2a/{scope}/.well-known/agent.json`
  - Review: `/a2a/supervisor/tasks/review/get|decide`
- A2A 메서드는 **v1.0(PascalCase)**과 **legacy(slash-case)** 동시 지원: `SendMessage` / `message/send`, `SendStreamingMessage` / `message/stream` 등
- A2UI 기능: `host.a2a.a2ui.enabled=true` 일 때만 compose 단계에서 시도
- Handoff: `host.a2a.handoff.enabled` 컨트롤, max-hops / rate-limit 제약 있음
- HITL: service 레벨 사전 게이트, graph 내부 대기 노드 없음

## 빌드 & 실행

```sh
./gradlew bootRun               # 실행
./gradlew test                   # 전체 테스트
./gradlew test --tests "*ClassName*"  # 단일 테스트 클래스
```

- Gradle 8.5, Java 21, Spring Boot 3.4.3, Spring AI 1.1.4, LangGraph4j 1.8.10, A2A SDK 1.0.0.Alpha4
- `.env` 파일 필요: `OPENAI_API_KEY`, `GEMINI_API_KEY`, `MISTRAL_API_KEY`, `REDIS_HOST`, `REDIS_PORT`

## 저장소 전환

`app.redis.enabled=true` → Redis, `false` → InMemory
영향: task store, review store, swarm store, conversation store, checkpoint store, idempotency. Redis 없이 true면 오류.

## 테스트 특징

- `src/test/java/com/example/architecture/PackageBoundaryTest.java` — 패키지 경계 위반 검증
- 테스트가 `main`과 동일한 패키지 구조를 미러링
- Redisson, Awaitility, Mockito 사용

## 문서 & PUML

아키텍처 PUML 다이어그램은 `documents/` 아래 두 디렉토리:
- `documents/mcp-orchestrator-architecture/` — sub-agent (springai)
- `documents/a2a-host_agent-architecture/` — supervisor (springsupervisorai)

소스 코드와 PUML 간 동기화는 수동 관리. `AGENTS.md` 업데이트 시에도 PUML 갱신이 필요할 수 있음.

## 참고

현재 README.md에 API/method 명세, 라우팅 설정 예시, 운영 주의사항이 상세히 기록되어 있음.
