# MCP Orchestrator 구현 충실도 평가 리포트

작성일: 2026-04-10  
대상 프로젝트: `spring-ai`  
평가 범위:
- 명세 문서: `/documents/mcp-orchestrator-architecture` 하위 `.md`, `.puml`
- 구현 코드: `src/main/java`, `src/main/resources`, `src/test/java`
- 검증 실행: `./gradlew_unix test` (성공)

## 1. 평가 요약

- 종합 점수: **80 / 100**
- 결론: 설계 축(Agentic 계층, scope 기반 제어, mixed MCP transport, LangGraph 오케스트레이션)은 전반적으로 잘 반영되었으나, 보안 로그 정책과 테스트 실효성에서 명세 대비 중요한 격차가 존재한다.

## 2. 영역별 점수 (0~100)

| 영역 | 점수 | 요약 |
|---|---:|---|
| 아키텍처/패키지 정합성 | 93 | 문서의 To-Be 패키지 구조와 포트/어댑터 구성이 대부분 반영됨 |
| 오케스트레이션/상태흐름 | 89 | `plan -> execute -> compose -> persist` 흐름과 반복 가드 구현됨 |
| MCP/설정/전송전략 | 91 | `mcp.yml` 분리, SSE+stdio 혼합, reconnect-first + cache-second 구현됨 |
| 보안/예외처리 | 72 | scope/allowlist/SSRF 방어는 양호하나 로그/예외 노출 정책에 불일치 존재 |
| SOLID/자바 코드원칙 | 79 | 책임 분리 전반 양호, 일부 DRY/OCP/경계 에러처리 약점 존재 |
| 테스트 충실도 | 58 | 핵심 MCP 테스트 다수 `@Disabled`로 회귀 방어력 낮음 |

## 3. 명세 대비 누락/불일치 항목

### 3.1 보안/운영 가드레일

1. **raw payload 로그 노출 가능**
   - 문서 정책: raw prompt/token/internal key/full MCP payload 로그 금지
   - 구현: MCP 실행 결과 preview를 info 로그로 기록
   - 근거: `McpToolExecutionService`의 payload preview 로깅
   - 파일: `src/main/java/com/example/springai/service/agent/execute/McpToolExecutionService.java`

2. **planner 원문 출력 로그 노출 가능**
   - 문서 정책: 내부 프롬프트/민감 출력 노출 최소화
   - 구현: planner output 및 repaired output 로그 기록
   - 파일: `src/main/java/com/example/springai/service/agent/plan/HeuristicPlanningService.java`

3. **예외 응답 sanitize 일관성 부족**
   - 문서 정책: 내부 상세정보 비노출
   - 구현: 일부 핸들러가 `ex.getMessage()`를 그대로 응답 본문에 사용
   - 파일: `src/main/java/com/example/springai/advice/GlobalExceptionHandler.java`

### 3.2 설계/리팩토링 일치도

1. **컨트롤러 공통 로직 추출 미반영**
   - 문서(26): `BaseAgentControllerSupport` 공통화 제안
   - 구현: Product/Reservation/Search 컨트롤러에 유사 코드 반복
   - 파일:
     - `src/main/java/com/example/springai/controller/ProductAgentController.java`
     - `src/main/java/com/example/springai/controller/ReservationAgentController.java`
     - `src/main/java/com/example/springai/controller/SearchAgentController.java`

### 3.3 테스트/품질 보증

1. **핵심 MCP 테스트 다수 비활성화**
   - 결과적으로 `test` 성공이 곧 MCP 런타임 안정성 보장을 의미하지 않음
   - 파일:
     - `src/test/java/com/example/springai/mcp/McpClientFactoryTest.java`
     - `src/test/java/com/example/springai/mcp/ProcessManagerTest.java`
     - `src/test/java/com/example/springai/mcp/McpProcessLauncherTest.java`
     - `src/test/java/com/example/springai/mcp/StdioMcpClientTest.java`

## 4. 강점 (명세 충족 항목)

1. **scope 정책 충족**
   - `HttpChatController`는 unrestricted 경로 유지
   - Product/Reservation/Search는 `AgentScopeResolver` 기반 restricted 경로 적용

2. **Agentic 계층 분리 충족**
   - `orchestrator/plan/execute/compose/store/security/runtime/prompt` 계층 분리 구현
   - 상위 흐름이 포트 중심으로 연결됨

3. **MCP 설정/런타임 정책 충족**
   - `mcp.yml` 분리
   - `sale-product`, `reservation`의 SSE host 반영
   - tool schema 로딩 전략(reconnect-first, cache-second, composite key) 반영

4. **LangGraph 기반 상태 흐름 충족**
   - `PLAN -> EXECUTE(optional) -> COMPOSE` 구성
   - 도구 반복 실행 상한 가드 존재

## 5. 심각도별 이슈 목록

## High

1. 민감 정보 노출 위험 로그(`McpToolExecutionService` payload preview)
2. 핵심 MCP 테스트 비활성화로 인한 회귀 리스크

## Medium

1. planner raw/repaired 출력 로그 노출면 증가
2. GlobalExceptionHandler의 예외 메시지 직접 노출
3. 신규 컨트롤러 3종 공통 로직 중복(DRY/OCP 저하)

## Low

1. 리팩토링 문서상 공통 컨트롤러(`BaseAgentControllerSupport`) 미적용

## 6. 최종 판정

- **구현 충실도: 높음(구조/기능 관점)**
- **운영 준비도: 보완 필요(보안 로그/테스트 관점)**

권고 우선순위:
1. 로그 민감정보 정책 정비 (High)
2. MCP 통합 테스트 재활성화 또는 대체 가능한 안정 테스트 분리 구축 (High)
3. 예외 응답 sanitize 규칙 일관화 (Medium)
4. 에이전트 컨트롤러 공통 로직 추출로 유지보수성 개선 (Medium)
