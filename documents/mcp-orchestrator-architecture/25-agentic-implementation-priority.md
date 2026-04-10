# 25. Agentic AI Implementation Priority

## 목표

- `puml`/`md` 설계 기준으로 Agentic AI Phase 1(`plan -> execute -> compose -> persist`)를 우선 구현한다.
- Redis는 로컬 Docker 컨테이너를 사용한다.
- UI는 `static/design_v7.html` 계약(`/api/http-chat/stream`)을 유지한다.
- 신규 3개 컨트롤러 대응 UI(`product/reservation/search-agent-chat.html`)를 제공한다.
- MCP 설정은 `application.yml`에서 `mcp.yml`로 분리한다.
- SOLID, 유지보수성, 가독성을 우선한다.

## 작업 우선순위

### P0. 구조 기반 확립 (필수 선행)

- `service.agent.*` 패키지와 핵심 포트 추가
  - `PlanningService`, `ToolExecutionService`, `ResponseComposeService`
  - `ConversationStore`, `GraphCheckpointStore`
- 도메인 모델 추가
  - `AgentChatRequest`, `PlanningContext`, `ToolPlan`, `ToolExecutionResult`, `ChunkType`, `AgentGraphState`
- 완료 기준
  - 컨트롤러/상위 서비스는 구현체가 아닌 포트(interface) 중심 의존

### P1. Redis 저장소 도입 (필수)

- `spring-boot-starter-data-redis` 추가
- Redis 기반 구현체 작성
  - `RedisConversationStore`
  - `RedisGraphCheckpointStore`
- 키 전략/TTL 적용
  - `agent:conv:{sessionId}`, `agent:ckpt:{sessionId}`
- 완료 기준
  - 세션 히스토리/체크포인트가 앱 재시작 후에도 Redis에서 복원됨

### P2. Agent 오케스트레이션 구현 (핵심 기능)

- `AgentOrchestrator` 구현
  - load history/checkpoint
  - plan
  - execute (optional)
  - compose stream
  - persist
- LangGraph4j는 현재 단계에서 경량 흐름으로 우선 적용(확장 가능한 노드 경계 유지)
- 완료 기준
  - `/api/http-chat/stream` 요청이 Agent 파이프라인을 통해 응답

### P3. MCP 연동 + 보안 가드레일

- 기존 `McpClientFactory` 재사용
- `SSE(sale-product/reservation)` + `stdio(search-mcp-server)` 혼합 전송 지원
- 서버/툴 allowlist 적용
- HttpChatController는 unrestricted, 신규 3개 컨트롤러는 scoped 접근 적용
- 실패 시 사용자 메시지 표준화(`HUMAN_MESSAGE`)
- 로그 마스킹 원칙 적용(토큰/raw payload 미노출)
- 완료 기준
  - 허용되지 않은 서버/툴 호출 차단
  - 예외가 내부정보 유출 없이 처리됨

### P4. UI 계약 검증 (design_v7.html)

- `design_v7.html`가 사용하는 요청/응답 포맷 유지
- 신규 HTML 클라이언트 3종이 각 컨트롤러 endpoint와 연결되는지 검증
- 스트리밍 실패 처리 메시지 일관화
- 완료 기준
  - 프론트 수정 최소화로 기존 화면에서 정상 대화 가능

### P5. 테스트 및 검증

- 단위 테스트
  - planner/executor/orchestrator/store
- 통합 테스트
  - `/api/http-chat/stream` + Redis 저장/복원
- 완료 기준
  - 핵심 경로 테스트 통과

### P6. A2A 코어 통합 (현재 버전 기준)

- A2A 컨트롤러 3종 + agent card endpoint 추가
- A2A JSON-RPC DTO/매퍼 추가
- `A2ATaskStore` 도입 + scope ownership 검증
- orchestrator lifecycle와 task 상태 동기화
- 완료 기준
  - `message/send`, `tasks/get`, `tasks/cancel`, `tasks/list` 정상 동작
  - 기존 `/api/*-agent/*` 회귀 100% 통과
  - 배포 게이트 기준 충족

## 구현 순서 (실행)

1. P0 구조 추가
2. P1 Redis 저장소 도입
3. P2 오케스트레이터 연결
4. P3 MCP + 보안 가드레일
5. P4 UI 계약 검증
6. P5 테스트 실행/수정
7. P6 A2A 코어 통합 + 호환성 게이트 검증

## 이번 턴 범위

- 현재 소스 기준으로 P0~P4는 반영 완료.
- P5는 MCP 프로세스 연동 테스트 일부가 `@Disabled` 상태로, 안정형 대체 테스트 확장이 필요.
- P6(A2A 코어 통합)는 1차 반영 완료이며, 잔여 과제는 회귀/호환성 게이트 자동화다.

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split 완료: `application.yml` imports `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

## 2026-04-10 A2A Priority Alignment (Doc 28)

- 버전 업그레이드 없이 현재 라인에서 A2A 통합을 우선 수행
- 기존 경로 무영향을 배포 게이트로 강제
