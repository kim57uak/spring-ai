# 18. Spring AI To Usecase Mapping

## Core Runtime Mapping

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

## Handoff

- handoff는 multi-agent 전환이 아니라 다음 tool group 노드 이동이다.
- 이동 조건은 `LangGraph4j` conditional edge로 관리한다.
