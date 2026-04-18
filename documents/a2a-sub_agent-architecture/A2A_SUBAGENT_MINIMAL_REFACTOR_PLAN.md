# A2A Sub-Agent Current Source Alignment

Last synchronized with source: 2026-04-18  
Source baseline: `src/main/java/com/example/springai`

## 1. Status

The original “minimal refactor plan” is largely implemented in current source.

Implemented now:
- `controller.a2a.BaseA2AControllerSupport`
- `ProductA2AController`
- `ReservationA2AController`
- `SearchA2AController`
- `AgentCardController`
- `a2a.dto.*`
- `A2aLifecycleService`
- `A2ATaskStore`
- `InMemoryA2ATaskStore`
- `RedisA2ATaskStore`
- `A2aRequestIdempotencyService`
- `A2AResponseMapper`
- `AgentCardRegistry`
- `ScopedAgentChatService` A2A-aware overloads
- `AgentOrchestrator` A2A lifecycle integration

## 2. Actual Endpoint Surface

- card endpoints
  - `GET /.well-known/agent.json`
  - `GET /a2a/{scope}/.well-known/agent.json`
- scope endpoints
  - `POST /a2a/product`
  - `POST /a2a/reservation`
  - `POST /a2a/search`
- stream alias
  - `POST /a2a/{scope}/stream`
- maintenance
  - `POST /a2a/{scope}/clear`

## 3. Current Request Compatibility

- accepted method families
  - `SendMessage` / `message/send`
  - `SendStreamingMessage` / `message/stream`
  - `GetTask` / `tasks/get`
  - `CancelTask` / `tasks/cancel`
  - `ListTasks` / `tasks/list`
- unary behavior
  - stream method is rejected from unary path
- stream behavior
  - `/a2a/{scope}` accepts stream methods when `Accept: text/event-stream`
  - `/a2a/{scope}/stream` remains backward-compatible alias

## 4. Current Runtime Flow

1. A2A controller validates JSON-RPC envelope and scope enablement.
2. `BaseA2AControllerSupport` resolves session id.
3. Unary `message/send` goes through `A2aRequestIdempotencyService`.
4. `A2aLifecycleService#createAndMarkRunning()` creates the task.
5. `ScopedAgentChatService` forwards request to `AgentOrchestrator`.
6. `AgentOrchestrator` runs graph/compose and marks task completed or failed.
7. `tasks/get`, `tasks/list`, `tasks/cancel` read/write through `A2aLifecycleService`.

## 5. Ownership and Isolation Rules In Source

- controller-to-controller forwarding does not exist
- scope boundaries are controller-level and task-store-level
- ownership checks use both:
  - `scopeName`
  - `sessionId`
- disabled scopes return:
  - 404 for scoped card endpoint
  - JSON-RPC error `-32004` for A2A request processing

## 6. What The Source Does Not Implement

- no global `/a2a` multi-scope router controller
- no separate “sub-agent process per scope”; scopes share one Spring Boot app
- no cross-agent forwarding inside `com.example.springai`

## 7. Documentation Guidance

When referring to current implementation, treat this document as the source-backed replacement for the old minimal refactor proposal. The old plan steps should not be read as pending work unless they are absent from the source listed above.
