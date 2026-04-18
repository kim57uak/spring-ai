# MCP Orchestrator Source Synchronization Report

Updated: 2026-04-18  
Source baseline: `src/main/java/com/example/springai`

## 1. Summary

- This report now tracks the current source, not the older 2026-04-10 target state.
- The `springai` runtime is already beyond the original “MCP orchestrator only” design and includes:
  - base HTTP controllers
  - scoped controllers
  - A2A JSON-RPC controllers
  - task lifecycle + idempotency
  - agent card exposure
  - Redis-backed history/checkpoint/task persistence

## 2. Confirmed Current Architecture

- Entry points
  - `/api/chat`
  - `/api/product-agent/*`
  - `/api/reservation-agent/*`
  - `/api/search-agent/*`
  - `/a2a/product`
  - `/a2a/reservation`
  - `/a2a/search`
  - `/.well-known/agent.json`
  - `/a2a/{scope}/.well-known/agent.json`
- Core runtime
  - `AgentOrchestrator`
  - `LangGraphAgentStateGraphFactory`
  - `HeuristicPlanningService`
  - `McpToolExecutionService`
  - `LlmResponseComposeService`
- MCP integration
  - mixed transport (`sse`, `stdio`)
  - reconnect-first schema loading
  - scope/server cache
  - static `allowTools` fallback
- A2A runtime
  - JSON-RPC unary + streaming support
  - `A2aLifecycleService`
  - `A2aRequestIdempotencyService`
  - `A2ATaskStore` with in-memory/Redis implementations
  - scope + session ownership checks

## 3. Key Source-backed Behaviors

- A2A unary requests reject stream methods on the unary path.
- A2A streaming methods are accepted on `/a2a/{scope}` and `/a2a/{scope}/stream`.
- `X-A2A-Session-Id` can override the HTTP session id for upstream supervisor integration.
- `AgentCardController` exposes only enabled scopes when `agent.cards.enabled-scopes` is configured.
- `GlobalExceptionHandler` returns JSON-RPC error envelopes for `/a2a/**` requests.
- `AgentOrchestrator` persists conversation and checkpoint state after compose, then updates A2A task state if `a2aContext` exists.

## 4. Residual Documentation Gaps Closed By This Sync

- outdated “to-be” package specs have been replaced with current package structure
- A2A playbook/sample docs now describe implemented endpoints and services
- PlantUML diagrams now include:
  - A2A controllers
  - lifecycle/idempotency
  - Redis/in-memory task store split
  - actual orchestrator sequencing

## 5. Remaining Source-vs-Doc Boundaries

- The `documents/mcp-orchestrator-architecture` folder still contains some historical planning documents.
- Those planning documents are intentionally not treated as source-of-truth architecture specs.
- The synchronized source-of-truth set for current implementation is now:
  - `03-component-architecture.puml`
  - `04-domain-class-model.puml`
  - `18-spring-ai-to-usecase-mapping.md`
  - `19-spring-ai-usecase-sequence.puml`
  - `20-mcp-package-class-spec.md`
  - this report
