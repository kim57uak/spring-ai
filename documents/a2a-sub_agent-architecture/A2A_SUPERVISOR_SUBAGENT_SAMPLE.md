# A2A Sub-Agent Sample (Aligned To Current `springai` Source)

Last synchronized with source: 2026-04-18  
Source baseline: `src/main/java/com/example/springai`

## 1. Current Host / Sub-Agent Shape

The current `springai` application is a single Spring Boot server exposing multiple A2A sub-agent scopes:

- `product`
- `reservation`
- `search`

Each scope has its own endpoint:

- `/a2a/product`
- `/a2a/reservation`
- `/a2a/search`

## 2. Agent Card Exposure Sample

### 2.1 Global card list

`GET /.well-known/agent.json`

Response shape:

```json
[
  {
    "name": "Product Agent",
    "description": "상품 도메인 처리 하위 에이전트",
    "version": "1.0.0",
    "supportedInterfaces": [
      {
        "transport": "JSONRPC",
        "url": "http://host:port/a2a/product"
      }
    ]
  },
  {
    "name": "Reservation Agent",
    "description": "예약 도메인 처리 하위 에이전트",
    "version": "1.0.0",
    "supportedInterfaces": [
      {
        "transport": "JSONRPC",
        "url": "http://host:port/a2a/reservation"
      }
    ]
  }
]
```

The actual list is filtered by `agent.cards.enabled-scopes`.

### 2.2 Scoped single card

`GET /a2a/product/.well-known/agent.json`

Response shape:

```json
{
  "name": "Product Agent",
  "description": "상품 도메인 처리 하위 에이전트",
  "version": "1.0.0",
  "supportedInterfaces": [
    {
      "transport": "JSONRPC",
      "url": "http://host:port/a2a/product"
    }
  ]
}
```

## 3. Unary Request Sample

`POST /a2a/product`

```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "method": "message/send",
  "params": {
    "messageText": "AAZ115260410OZ1 상품 정보 조회",
    "model": "openai"
  }
}
```

Controller behavior in source:

1. validate JSON-RPC envelope
2. resolve session id
3. apply idempotency for unary send
4. create A2A task and mark running
5. call `ScopedAgentChatService`
6. return `JsonRpcResponse` with `TaskView`

## 4. Streaming Request Sample

`POST /a2a/product`

Headers:

```text
Accept: text/event-stream
X-A2A-Session-Id: supervisor-session-123
```

Body:

```json
{
  "jsonrpc": "2.0",
  "id": "stream-1",
  "method": "message/stream",
  "params": {
    "messageText": "상품 추천해줘",
    "model": "openai"
  }
}
```

Current source behavior:

- stream methods are served from the main `/a2a/{scope}` endpoint
- `/a2a/{scope}/stream` is still accepted as alias
- stream completion appends `[DONE]`

## 5. Task Query Sample

`POST /a2a/product`

```json
{
  "jsonrpc": "2.0",
  "id": "task-get-1",
  "method": "tasks/get",
  "params": {
    "id": "task-123"
  }
}
```

Result shape:

```json
{
  "jsonrpc": "2.0",
  "id": "task-get-1",
  "result": {
    "id": "task-123",
    "status": "COMPLETED",
    "scope": "product",
    "createdAt": "2026-04-18T10:00:00Z",
    "updatedAt": "2026-04-18T10:00:03Z",
    "response": "..."
  }
}
```

## 6. Source-backed Notes

- This application does not implement a supervisor router inside `com.example.springai`.
- It implements sub-agent endpoints only.
- Supervisor integration is expected to happen by calling these A2A endpoints from outside this module.
