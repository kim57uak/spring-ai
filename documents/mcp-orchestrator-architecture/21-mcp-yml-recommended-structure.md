# 21. MCP Recommended Structure

## Current Source Shape

현재 `application.yml`은 아래 구조를 사용한다.

- `mcp.servers.search-mcp-server`
- `mcp.servers.search-economy-index`
- `mcp.servers.hanatourApi`

## Recommended Shape (Platform-Neutral)

```yaml
mcp:
  servers:
    research-tools:
      command: ${MCP_RESEARCH_COMMAND}
      args:
        - ${MCP_RESEARCH_ARG_1}
      env: {}
      capabilities:
        - knowledge-retrieval
    market-tools:
      command: ${MCP_MARKET_COMMAND}
      args:
        - ${MCP_MARKET_ARG_1}
      env: {}
      capabilities:
        - live-data-query
```

## Rules

- server는 `capabilities` 메타데이터를 가진다.
- planner는 capability를 선택하고 executor가 실제 tool을 선택한다.
- 명시적 allowlist에 없는 tool은 실행하지 않는다.
- command/arg/env는 설정으로만 주입하고 코드에 하드코딩하지 않는다.
