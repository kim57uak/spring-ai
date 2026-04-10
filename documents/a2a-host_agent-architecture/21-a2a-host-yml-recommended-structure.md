# 21. A2A Host YML Recommended Structure

```yaml
host:
  a2a:
    routing:
      product:
        endpoint: http://localhost:8082/a2a/product
        method: message/send
        timeout-ms: 10000
      reservation:
        endpoint: http://localhost:8082/a2a/reservation
        method: message/send
        timeout-ms: 10000
      search:
        endpoint: http://localhost:8082/a2a/search
        method: message/send
        timeout-ms: 10000
    retry:
      max-retries: 1
      initial-backoff-ms: 500
      max-backoff-ms: 3000
```

## Rules

- allowlist에 없는 endpoint는 호출 금지
- method는 `message/send`, `message/stream`, `tasks/*`만 허용
- timeout/retry는 host 경계에서 일원화

