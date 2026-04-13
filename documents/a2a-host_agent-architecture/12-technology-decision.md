# 12. Supervisor Agent Technology Decision

## 30/31 통합 반영 결정

- 오케스트레이션 방식은 `LangGraph4j + Swarm State Store` 하이브리드로 확정한다.
- HITL은 현재 단계에서 `APPROVE/CANCEL`만 지원한다.
- 데이터 생성/변경(create/update/delete) 요청은 점수 기반과 무관하게 HITL 강제 정책을 적용한다.
- A2A 메서드 호환은 `legacy + v1.0` 동시 지원을 기본 정책으로 한다.
- handoff는 기능 플래그(`handoff.enabled`) 기반으로 점진 적용한다(기본 OFF).
- handoff method는 기존 허용 enum만 허용하고 stream 미지원 agent 대상 stream handoff는 금지한다.
- 생각과정/진행상태 표시는 `SupervisorProgressSupport` 공통 모듈로 통일한다.

## Final Choice

- `Spring AI + LangGraph4j + Redis + A2A(JSON-RPC/SSE)`

## Why

- `Spring AI`: supervisor agent planning/compose에 필요한 모델 추상화 유지
- `LangGraph4j`: 라우팅/호출/병합 흐름을 상태그래프로 강제
- `Redis`: supervisor 세션 히스토리/체크포인트 외부화
- `A2A`: 하위 에이전트 내부 구현과 분리된 안정 경계 제공

## Scope

- Supervisor agent는 하위 에이전트를 A2A로만 호출한다.
- 하위 에이전트 내부 로직/툴/MCP는 설계 범위에서 제외한다.
- 기존 프로젝트의 LLM 호출 정책(`LlmCallPolicy`, rate-limit)과 동일 원칙을 사용한다.


---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.

---

## 2026-04-13 동기화 메모 (34 반영)

- Supervisor 오케스트레이션에 handoff 분기(`invoke -> handoff evaluate/apply`)를 도입한다.
- 리팩토링 기준은 SOLID/추상화/가독성/유지보수성 우선 원칙을 따른다.
- 신규/수정 public API 및 핵심 메서드에 Javadoc을 필수 적용한다.
