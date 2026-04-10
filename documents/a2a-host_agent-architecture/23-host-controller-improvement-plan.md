# 23. Host A2AController Improvement Plan

## Goal

- `HostA2AController` 단일 진입점에서 JSON-RPC/A2A 계약을 안정적으로 처리한다.

## Plan

1. 입력 검증 강화
- method allowlist 검증
- params schema 검증

2. 오류 일관화
- JSON-RPC error code 매핑 표준화
- 내부 예외 메시지 sanitize

3. 스트리밍 안정화
- `message/stream` chunk framing 규칙 고정
- 취소/타임아웃 시 종료 이벤트 규칙 통일

