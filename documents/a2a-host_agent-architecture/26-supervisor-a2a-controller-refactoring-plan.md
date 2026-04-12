# 26. Supervisor A2A Controller Refactoring Plan

## Objective

- 슈퍼바이저 에이전트의 유일한 진입점인 `SupervisorA2AController`를 기준으로 구조를 단순화한다.

## Refactoring Rules

- 일반 HTTP 컨트롤러 추가 금지
- `/a2a/*` 계약만 유지
- orchestration/라우팅 로직은 controller에서 제거하고 service/orchestrator로 이동

## Tasks

1. `SupervisorA2AController` 메서드 책임 정리
- precheck(jsonrpc/method/params)
- service 위임
- 응답 envelope 직렬화

2. 에러 정책 통합
- `GlobalExceptionHandler`에서 A2A 오류 코드 일괄 매핑

3. 테스트 정비
- method별 정상/오류/취소 시나리오 추가

## Done Criteria

- 컨트롤러는 protocol adapter 역할만 수행
- 비즈니스 로직은 service/orchestrator로 분리
- `message/send`, `message/stream`, `tasks/*` 회귀 통과
