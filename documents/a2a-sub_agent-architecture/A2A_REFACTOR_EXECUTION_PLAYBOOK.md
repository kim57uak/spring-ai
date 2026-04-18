# A2A Refactor Execution Playbook (Current Source)

Last synchronized with source: 2026-04-18  
Source baseline: `src/main/java/com/example/springai`

## 1. Purpose

This playbook is now a maintenance/reference document for the current A2A implementation, not a future refactor checklist.

## 2. Implemented Building Blocks

- protocol/controller layer
  - `BaseA2AControllerSupport`
  - `ProductA2AController`
  - `ReservationA2AController`
  - `SearchA2AController`
  - `AgentCardController`
- lifecycle/task layer
  - `A2aLifecycleService`
  - `A2ATaskStore`
  - `InMemoryA2ATaskStore`
  - `RedisA2ATaskStore`
- compatibility/support
  - `A2aRequestIdempotencyService`
  - `A2AResponseMapper`
  - `AgentCardRegistry`
- orchestrator integration
  - `ScopedAgentChatService`
  - `AgentOrchestrator`

## 3. Operational Rules Backed By Source

- `controller/base` and `controller/a2a` are separate paths and do not forward to each other.
- A2A controllers are additive; base `/api/*` endpoints remain intact.
- A2A errors on `/a2a/**` are converted by `GlobalExceptionHandler` into JSON-RPC envelopes.
- card exposure is controlled by:
  - `agent.scopes.*`
  - `agent.cards.enabled-scopes`
- task ownership is enforced by `scope + sessionId`.
- streaming timeout protection exists in `BaseA2AControllerSupport` (`110s`) and emits `[DONE]`.

## 4. Current Verification Checklist

- card exposure
  - `A2aAgentCardExposureTest`
- protocol parsing and method compatibility
  - `A2aApiTest`
- task lifecycle transition behavior
  - `A2aLifecycleFlowTest`
- orchestrator lifecycle hookup
  - `A2aLifecycleService` + `AgentOrchestrator`

## 5. Current Known Constraints

- one Spring Boot application hosts multiple sub-agent scopes
- no `/a2a` global router abstraction is implemented
- streaming cancellation is handled by task state + orchestrator checks, not by a separate distributed cancel bus

## 6. If Further Refactoring Is Needed

Apply changes in this order:

1. keep `controller/base` behavior unchanged
2. change `controller/a2a` and `a2a.*` first
3. change `ScopedAgentChatService` / `AgentOrchestrator` only if lifecycle semantics need to change
4. update `A2aApiTest`, `A2aAgentCardExposureTest`, `A2aLifecycleFlowTest`
5. then update docs under this folder
