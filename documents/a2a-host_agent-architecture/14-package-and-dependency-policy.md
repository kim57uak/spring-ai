# 14. Supervisor Agent Package And Dependency Policy

## Base Package

- `com.example.springsupervisorai`

## Dependency Rules

- `controller -> service -> orchestrator -> ports(plan/invoke/compose/store/graph)`
- `invoke -> a2a client/registry` only
- `plan/compose -> runtime(DefaultSupervisorLlmRuntime)` only
- 상위 계층은 구현체가 아니라 interface에 의존한다.

## Design Rules

- Host는 하위 agent 내부 계약에 직접 의존하지 않는다.
- 하위 agent 호출은 반드시 A2A endpoint allowlist를 통과해야 한다.
- 실패 응답은 supervisor 표준 메시지로 정규화한다.
- supervisor 진입점은 `/a2a/supervisor`(stream 포함)만 사용한다.
