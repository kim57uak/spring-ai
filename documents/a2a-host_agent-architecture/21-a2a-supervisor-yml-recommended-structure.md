# 21. A2A Supervisor YML Recommended Structure

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
    circuit-breaker:
      enabled: true
      failure-threshold: 3
      open-duration-ms: 30000
    execution:
      max-concurrency: 1
    history:
      max-turns: 5
    hitl:
      reason-messages:
        reservation_creation_request: "예약 생성 요청으로 판단되어 사용자 승인이 필요합니다."
        data_mutation_detected: "데이터 변경 요청으로 판단되어 사용자 승인이 필요합니다."
        default: "요청 처리 전 사용자가 검토해야 하는 상황으로 판단되었습니다."
    a2ui:
      enabled: false
    handoff:
      enabled: false
      max-hops: 3
      block-same-agent-within-steps: 2
      max-per-minute: 10
      allow-methods:
        - message/send
        - message/stream
```

## Rules

- allowlist에 없는 endpoint는 호출 금지
- method는 `legacy + v1.0` 호환 기준(`message/send`, `SendMessage`, `message/stream`, `SendStreamingMessage`, `tasks/*`)으로 허용
- timeout/retry는 supervisor 경계에서 일원화
- 상품/예약/주문 생성·변경 요청은 `hitl.mandatory-data-mutation=true`일 때 무조건 review 대기
- `handoff.enabled=false`면 handoff directive는 실행하지 않고 skip 이벤트만 기록
- handoff method는 허용 enum만 통과시키며 stream 미지원 agent 대상 stream handoff는 차단
- handoff 진행상태는 `SupervisorProgressSupport` 공통 포맷(`stage/progress/message/metadata`)으로 출력


---

## 2026-04-13 동기화 메모 (34 반영)

- handoff 기능은 기본 OFF로 배포하고 canary부터 점진 활성화한다.
- handoff 정책값(`max-hops`, `max-per-minute`, `allow-methods`)은 운영 중 조정 가능해야 한다.
- 공통 progress 모듈을 통해 생각과정 UI에 handoff 단계를 노출한다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
