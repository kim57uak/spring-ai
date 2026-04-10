# 18. Spring AI To Usecase Mapping

## Core Runtime Mapping

- `DefaultAgentLlmRuntime`
  - agent 계층의 단일 LLM 포트이며, model 문자열을 `ChatModelType`으로 변환한다.
- `ModelChatServiceFactory`
  - `ChatModelType` 기반으로 sync/stream/structured 구현체를 라우팅한다.
- `SpringAiCompatibleChatService` + `service.chat.model.*`
  - OpenAI 호환 endpoint를 공통 호출하며 provider별(`openai`, `gemini`, `gemini-lite`, `mistral`) 설정을 캡슐화한다.
- `LlmCallPolicy` / `LlmRequestRateLimiter`
  - 최소 호출 간격, 429/5xx 재시도, 백오프 정책을 공통 적용한다.
- `PlanningService`
  - Spring AI를 사용해 사용자 질의 + 최근 히스토리에서 다음 액션을 선택한다.
- `ResponseComposeService`
  - tool 실행 결과를 사용자 응답으로 합성한다.
- `PromptTemplateService`
  - planning/compose/human-message 프롬프트를 구성한다.
- `AgentLlmRuntime`
  - provider API를 숨기는 플랫폼 중립 포트다.

## Usecase Group (Platform-Neutral)

- `knowledge-retrieval`
  - 검색/문서 조회/요약 계열
- `live-data-query`
  - 시세/지표/실시간 데이터 조회 계열
- `action-execution`
  - 외부 시스템 변경 요청 계열

## MCP Mapping (Current Source Example)

- `search-mcp-server`
  - 일반 검색/리서치 tool group
- `search-economy-index`
  - 경제/지표 조회 tool group

## History and State

- planner 입력에는 최근 conversation history가 포함된다.
- graph checkpoint는 Redis에 저장해 재시작 후 resume 가능해야 한다.
- 상위 계층은 `ConversationStore`, `GraphCheckpointStore` 인터페이스만 의존한다.
- 요청별 컨텍스트(`ChatRequestContext`)로 `sessionId`, `requestedModel`, `mcpToolCallbacksEnabled`를 전달한다.

## Handoff

- handoff는 multi-agent 전환이 아니라 다음 tool group 노드 이동이다.
- 이동 조건은 `LangGraph4j` conditional edge로 관리한다.

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split: `application.yml` -> `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key

