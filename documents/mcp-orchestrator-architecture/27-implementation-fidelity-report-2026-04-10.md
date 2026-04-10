# MCP Orchestrator 구현 충실도 평가 리포트

작성일: 2026-04-11  
대상 프로젝트: `spring-ai`  
평가 범위:
- 명세 문서: `/documents/mcp-orchestrator-architecture` 하위 `.md`, `.puml`
- 구현 코드: `src/main/java`, `src/main/resources`, `src/test/java`
- 검증 실행: `./gradlew_unix test`

## 1. 평가 요약

- 종합 점수: **90 / 100**
- 결론: Agentic 계층, scope 기반 제어, mixed MCP transport, A2A 코어 연동은 구현 반영도가 높다. 주요 잔여 리스크는 MCP 환경의존 테스트 비활성화와 일부 운영 로그 상세도다.

## 2. 영역별 점수 (0~100)

| 영역 | 점수 | 요약 |
|---|---:|---|
| 아키텍처/패키지 정합성 | 96 | `controller.base`/`controller.a2a` 분리와 agent 포트 계층이 문서 구조와 정합 |
| 오케스트레이션/상태흐름 | 93 | `plan -> execute(optional) -> compose -> persist` + checkpoint 연계 반영 |
| MCP/설정/전송전략 | 94 | `mcp.yml` 분리, `sse+stdio`, reconnect-first/cache fallback 반영 |
| A2A 코어 통합 | 90 | agent card, scoped JSON-RPC, task lifecycle, ownership 검증 반영 |
| 보안/예외처리 | 84 | scope/allowlist/SSRF/예외 sanitize 반영, payload preview 로그는 추가 축소 권장 |
| 테스트 충실도 | 73 | 핵심 MCP 연동 테스트 일부 `@Disabled`로 운영 회귀 방어력 제한 |

## 3. 명세 대비 불일치/보완 항목

### High

1. **MCP 연동 테스트 비활성화**
   - 파일:
     - `src/test/java/com/example/springai/mcp/McpClientFactoryTest.java`
     - `src/test/java/com/example/springai/mcp/ProcessManagerTest.java`
     - `src/test/java/com/example/springai/mcp/McpProcessLauncherTest.java`
     - `src/test/java/com/example/springai/mcp/StdioMcpClientTest.java`
   - 영향: 단위/통합 테스트 성공이 MCP 실제 런타임 안정성을 충분히 보장하지 못함

### Medium

1. **운영 로그 노출면**
   - `McpToolExecutionService`에서 payload preview 로그 기록
   - 정책상 민감정보 최소화를 위해 preview/길이 기반 로그 추가 축소 권장

2. **A2A 회귀 자동화 범위**
   - A2A 핵심 기능은 구현되어 있으나, 배포 게이트 수준의 자동화 케이스는 추가 강화 필요

## 4. 강점 (명세 충족 항목)

1. **컨트롤러 정책 분리**
   - `HttpChatController`: unrestricted scope
   - `Product/Reservation/SearchAgentController`: restricted scope
   - 공통 로직은 `BaseAgentControllerSupport`로 통합

2. **Agentic 계층 분리**
   - `orchestrator/plan/execute/compose/store/security/runtime/prompt` 계층 분리
   - 상위 계층은 포트 중심 의존

3. **MCP 런타임 전략**
   - `McpClientFactory`에서 transport(`sse`/`stdio`) 분기
   - `ToolSchemaRegistry`의 reconnect-first + cache fallback
   - `application.yml` -> `mcp.yml` 분리

4. **A2A 코어 통합**
   - `/.well-known/agent.json`, `/a2a/{scope}` 반영
   - `message/send`, `message/stream`, `tasks/get`, `tasks/cancel`, `tasks/list`
   - `A2aLifecycleService` + `A2ATaskStore` + scope ownership 검증 반영

## 5. 최종 판정

- **구현 충실도: 매우 높음**
- **운영 준비도: 높음 (테스트 자동화 보완 필요)**

권고 우선순위:
1. MCP 환경의존 테스트를 CI 친화 시나리오로 재구성하거나 대체 통합 테스트 추가
2. MCP payload preview 로그의 마스킹/요약 정책 강화
3. A2A 회귀 게이트 자동화(기존 `/api/*` 무영향 + scope ownership)
