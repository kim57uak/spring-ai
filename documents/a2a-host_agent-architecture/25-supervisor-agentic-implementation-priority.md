# 25. Supervisor Agentic Implementation Priority

## P0

- `SupervisorA2AController -> SupervisorAgentService -> SupervisorAgentOrchestrator` 경로 확립
- `tasks/review/get`, `tasks/review/decide` 계약 추가 (`APPROVE/CANCEL`만)
- 상품/예약/주문 생성·변경 요청 강제 HITL 정책 추가

## P1

- LangGraph4j 상태그래프(`plan -> risk_assess -> hitl_gate -> wait_review -> apply_review -> select -> invoke -> merge -> compose`) 구현

## P2

- `A2AInvocationService` + endpoint allowlist + timeout/retry 적용
- `legacy + v1.0` 메서드 호환(enum 정규화) 강화
- `SupervisorSwarmStateStore` 도입 및 stateVersion/eventLog 저장 정책 적용

## P3

- 응답 합성 품질 개선(부분 실패 허용/요약 품질)

## P4

- 회귀/계약 테스트 자동화(`message/send`, `message/stream`, `tasks/*`)


---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
