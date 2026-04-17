# 32. SwarmState 및 Circuit Breaker 통합 현행 가이드

## 1) 목적

본 문서는 현재 구현 기준으로 supervisor의 `SwarmState`, circuit breaker, cooldown, handoff 상태 기록이 어떻게 연결되는지 설명한다.

- 기준 클래스
  - `DefaultSupervisorSwarmCoordinator`
  - `DefaultA2AInvocationService`
  - `InMemorySupervisorSwarmStateStore`
  - `RedisSupervisorSwarmStateStore`

---

## 2) SwarmState 역할

`SwarmState`는 세션 실행의 공유 facts와 이벤트 로그를 보존하는 스냅샷이다.

```java
public record SwarmState(
    String taskId,
    String sessionId,
    long stateVersion,
    Instant updatedAt,
    Map<String, Object> sharedFacts,
    List<Map<String, Object>> eventLog
)
```

현재 역할:

- routing 단계 필터링에 필요한 cooldown/circuit 정보 보관
- invoke 결과 요약 보관
- handoff hop/path/window 보관
- hitl policy/review 결과 보관
- graph 노드 실행 및 정책 이벤트 감사 로그 보관

SwarmState는 graph checkpoint와 별개이며, checkpoint는 `GraphCheckpointStore`, 공유 상태는 `SupervisorSwarmStateStore`가 담당한다.

---

## 3) 저장소 구현

### 3.1 InMemory 저장소

- 기본 단일 인스턴스용 구현
- taskId 기준 저장
- sessionId 기준 latest 조회 지원

### 3.2 Redis 저장소

- 분산 환경용 구현
- Redis keyspace + TTL 사용
- sessionId 기준 latest lookup 지원

문서상 중요한 점은 저장소 종류보다 `stateVersion` 기반 정합성 규칙이 공통이라는 것이다.

---

## 4) 낙관적 락과 재시도

현재 `DefaultSupervisorSwarmCoordinator`는 단순 upsert가 아니라 충돌 재시도를 수행한다.

- 최대 재시도 횟수: 3
- backoff: 선형 짧은 대기(`10ms * attempt`)
- 초과 시 `SwarmStateVersionConflictException` 전파

즉, handoff나 invoke batch 같은 누적 이벤트 업데이트는 "read-merge-write + version conflict retry" 방식으로 동작한다.

이 부분은 기존 설계 문서보다 현재 구현이 더 구체적이다.

---

## 5) 공유 facts 구조

현재 coordinator가 다루는 핵심 fact 키는 아래와 같다.

- `agentCooldownUntilEpochMs`
- `circuitBreakerOpenUntilEpochMs`
- `handoffHopCount`
- `handoffPath`
- `handoffBlockedCount`
- `lastHandoffAgent`
- `lastHandoffAt`
- `handoffWindowStartEpochMs`
- `handoffWindowCount`
- `lastInvokeFailedCount`
- `lastInvokeSuccessCount`
- `lastInvokeHandoffRequestedCount`
- `lastInvokeHandoffAcceptedCount`
- `hitlRequired`
- `policyId`
- `policyReason`
- `hitlDecision`
- `decisionReason`

예시:

```json
{
  "sharedFacts": {
    "agentCooldownUntilEpochMs": {
      "product": 1776385200000
    },
    "circuitBreakerOpenUntilEpochMs": {
      "reservation": 1776385110000
    },
    "handoffHopCount": 1,
    "handoffPath": ["search", "product"],
    "handoffBlockedCount": 2,
    "lastHandoffAgent": "product",
    "lastHandoffAt": "2026-04-17T10:00:00Z",
    "handoffWindowStartEpochMs": 1776385000000,
    "handoffWindowCount": 1,
    "lastInvokeFailedCount": 1,
    "lastInvokeSuccessCount": 2,
    "lastInvokeHandoffRequestedCount": 1,
    "lastInvokeHandoffAcceptedCount": 1
  }
}
```

---

## 6) Circuit Breaker와 Cooldown의 실제 분담

### 6.1 Circuit Breaker

`DefaultA2AInvocationService`가 agent별 in-memory circuit state를 관리한다.

- 연속 실패 횟수 누적
- threshold 도달 시 open
- open 중이면 downstream 호출 전에 즉시 실패 반환
- 성공 시 해당 agent circuit 상태 초기화

설정:

- `host.a2a.circuit-breaker.enabled`
- `host.a2a.circuit-breaker.failure-threshold`
- `host.a2a.circuit-breaker.open-duration-ms`

### 6.2 Swarm Cooldown

`DefaultSupervisorSwarmCoordinator.recordInvocationBatch(...)`가 invoke 결과를 기준으로 cooldown map을 갱신한다.

- 실패한 agent는 120초 cooldown
- 성공한 agent는 cooldown 해제

### 6.3 두 메커니즘의 연결

실제 실행 흐름은 아래와 같다.

1. invoke 시 circuit open 여부를 먼저 검사
2. open이면 즉시 `FAILED/CIRCUIT_OPEN`
3. invoke batch 결과가 SwarmState에 기록되며 cooldown 갱신
4. 다음 planning 단계에서 Swarm routing rule이 cooldown/circuit 정보를 보고 agent를 필터링

즉:

- circuit breaker는 invoke 단계의 hard block
- swarm cooldown은 planning 단계의 soft skip

---

## 7) 라우팅 필터링 규칙

`applyRoutingRule(...)`는 아래 순서로 동작한다.

1. `circuitBreakerOpenUntilEpochMs` 확인
2. `agentCooldownUntilEpochMs` 확인
3. 둘 중 하나라도 active이면 해당 agent를 계획에서 제외
4. 모두 차단되어 filtered plan이 비면 첫 번째 원본 plan을 강제 허용

강제 허용은 완전 무응답을 막기 위한 안전장치다.

동시에 coordinator는 아래 이벤트를 남긴다.

- circuit로 스킵된 agent 목록
- cooldown으로 스킵된 agent 목록
- forced plan 여부

---

## 8) 이벤트 로그

현재 event log는 최근 100개까지만 유지된다.

대표 이벤트 타입:

- `GRAPH_NODE_EVENT`
- `INVOKE_BATCH_RECORDED`
- `HANDOFF_REQUESTED`
- `HANDOFF_ACCEPTED`
- `HANDOFF_REJECTED`
- `HANDOFF_SKIPPED_BY_FLAG`
- `HITL_REVIEW_OPENED`
- `HITL_REVIEW_DECIDED`

참고:

- `PLAN`, `SELECT`, `INVOKE` 같은 값은 event type이 아니라 `GRAPH_NODE_EVENT`의 metadata `nodeType`으로 기록된다.
- 이전 문서에서 node명을 event type처럼 적은 부분은 현재 구현과 달랐다.

---

## 9) Handoff와 SwarmState 통합

`recordHandoffEvaluations(...)`는 검증 결과를 기준으로 아래를 업데이트한다.

- accepted 수에 따른 `handoffHopCount`
- 최근 handoff path
- 마지막 handoff agent/시간
- 분당 handoff window 카운트
- rejected 수에 따른 blocked count

그리고 아래 이벤트를 누적한다.

- directive 발견 시 `HANDOFF_REQUESTED`
- 검증 통과 시 `HANDOFF_ACCEPTED`
- 정책 차단 시 `HANDOFF_REJECTED`
- feature flag off면 `HANDOFF_SKIPPED_BY_FLAG`

현재 구현은 "nextAgentKey가 존재한다"가 아니라 `HandoffValidationResult.accepted()`를 기준으로 accepted를 계산한다.

---

## 10) 운영 관점 요약

현재 구조를 운영 관점에서 요약하면 다음과 같다.

- 호출 차단: circuit breaker
- 다음 계획 우회: swarm cooldown/circuit fact
- 이관 루프 제어: handoff hop/path/window fact
- 감사 추적: event log
- 동시성 보정: version conflict retry

즉, SwarmState는 단순 로그 저장소가 아니라 routing 안정화와 handoff 제어에 직접 참여하는 운영 상태 저장소다.
