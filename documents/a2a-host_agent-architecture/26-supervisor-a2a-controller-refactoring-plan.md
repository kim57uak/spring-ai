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
- `tasks/review/get`, `tasks/review/decide` method 분기 추가
- decision 입력값 `APPROVE/CANCEL` 검증 추가

2. 에러 정책 통합
- `GlobalExceptionHandler`에서 A2A 오류 코드 일괄 매핑

3. 테스트 정비
- method별 정상/오류/취소 시나리오 추가
- `legacy + v1.0` 메서드 alias 호환 테스트 추가
- Swarm `stateVersion` 충돌 시나리오 테스트 추가

## Done Criteria

- 컨트롤러는 protocol adapter 역할만 수행
- 비즈니스 로직은 service/orchestrator로 분리
- `message/send`, `message/stream`, `tasks/*` 회귀 통과

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
