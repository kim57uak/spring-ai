# 25. Host Agentic Implementation Priority

## P0

- `HostA2AController -> HostAgentService -> HostAgentOrchestrator` 경로 확립

## P1

- LangGraph4j 상태그래프(`plan -> select -> invoke -> merge -> compose`) 구현

## P2

- `A2AInvocationService` + endpoint allowlist + timeout/retry 적용

## P3

- 응답 합성 품질 개선(부분 실패 허용/요약 품질)

## P4

- 회귀/계약 테스트 자동화(`message/send`, `message/stream`, `tasks/*`)

