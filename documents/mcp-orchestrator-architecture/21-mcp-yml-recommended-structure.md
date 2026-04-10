# 21. MCP Recommended Structure

## Current Source Shape

현재는 `application.yml`에 MCP 설정이 있으나, 리팩토링 기준선에서는 `mcp.yml`로 분리한다.

- `mcp.servers.search-mcp-server`
- `mcp.servers.search-economy-index`
- `mcp.servers.hanatourApi`

## Recommended Shape (mcp.yml)

```yaml
mcp:
  servers:
    sale-product:
      transport: sse
      host: http://10.225.18.50:8080
      capabilities:
        - action-execution
      allow-tools:
        - createAutoCopySaleProducts
        - getSaleProductDetails
    reservation:
      transport: sse
      host: http://10.225.18.50:8080
      capabilities:
        - action-execution
      allow-tools:
        - createReservation
    search-mcp-server:
      transport: stdio
      command: ${MCP_NODE_PATH:/opt/homebrew/bin/node}
      args:
        - ${MCP_SEARCH_SERVER_PATH}
      env:
        PERPLEXITY_API_KEY: ${PERPLEXITY_API_KEY}
      capabilities:
        - knowledge-retrieval
      allow-tools: []

agent:
  scopes:
    product:
      allowed-servers: [sale-product]
      allowed-tools-by-server:
        sale-product: [createAutoCopySaleProducts, getSaleProductDetails]
    reservation:
      allowed-servers: [reservation]
      allowed-tools-by-server:
        reservation: [createReservation]
    search:
      allowed-servers: [search-mcp-server]
      allowed-tools-by-server: {}
```

## Rules

- server는 `capabilities` 메타데이터를 가진다.
- planner는 capability를 선택하고 executor가 실제 tool을 선택한다.
- 명시적 allowlist에 없는 tool은 실행하지 않는다.
- `transport`로 `sse`/`stdio`를 구분한다.
- command/arg/env/host는 설정으로만 주입하고 코드에 하드코딩하지 않는다.
- tool schema 캐시 키는 복합키를 사용한다.
  - `transport|serverName|endpointOrCommandSignature|toolSetHash|scopeHash`
- `allowed-servers`는 YAML 배열을 권장한다.
  - 예: `allowed-servers: [sale-product, reservation, search-mcp-server]`
  - 환경변수/프로퍼티 문자열 바인딩 시에는 콤마 구분도 가능하다.

## 2026-04-10 Alignment (Doc 26)

- HttpChatController: unrestricted MCP access
- Product/Reservation/Search: scoped MCP access (`allowedServers`, `allowedToolsByServer`)
- `sale-product`, `reservation`: SSE host `http://10.225.18.50:8080`
- MCP settings split: `application.yml` -> `mcp.yml`
- Tool schema loading: reconnect-first, cache-second, unique composite cache key
