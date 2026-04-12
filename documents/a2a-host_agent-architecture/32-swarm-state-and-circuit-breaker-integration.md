# SwarmState 및 Circuit Breaker 통합 가이드

## 개요

본 문서는 Supervisor의 **SwarmState** 기반 분산 상태 관리와 **Circuit Breaker** 패턴의 통합 구조를 설명합니다.

---

## 1. SwarmState 아키텍처

### 1.1 핵심 개념

**SwarmState**는 supervisor 세션의 공유 상태를 관리하는 스냅샷 객체입니다.

```java
public record SwarmState(
    String taskId,
    String sessionId,
    long stateVersion,        // 낙관적 락 버전
    Instant updatedAt,
    Map<String, Object> sharedFacts,  // 공유 팩트/컨텍스트
    List<Map<String, Object>> eventLog  // 이벤트 로그
)
```

### 1.2 저장소 구현체

#### InMemorySupervisorSwarmStateStore (기본값)

- **활성화 조건**: `spring.data.redis.enabled=false` 또는 미설정
- **용도**: 단일 인스턴스 환경
- **동시성 제어**: taskId별 ReentrantLock
- **메모리 관리**:
  - TTL: 1시간 자동 만료
  - 최대 항목: 10,000개
  - 초과 시 가장 오래된 20% 자동 제거

#### RedisSupervisorSwarmStateStore (분산 환경)

- **활성화 조건**: `spring.data.redis.enabled=true`
- **용도**: 다중 인스턴스 환경
- **동시성 제어**: Redis 트랜잭션 기반 낙관적 락
- **메모리 관리**: Redis TTL (기본 1시간)
- **인덱스**: sessionId → taskId 매핑

```yaml
# application.yml
spring:
  data:
    redis:
      enabled: true  # Redis 저장소 활성화
      host: localhost
      port: 6379
```

---

## 2. 낙관적 락 (Optimistic Locking)

### 2.1 동작 원리

SwarmState는 `stateVersion` 필드를 통해 동시 수정 충돌을 감지합니다.

```java
// 충돌 감지 로직
if (state.stateVersion() > 0 && current != null) {
    long expectedPreviousVersion = state.stateVersion() - 1;
    if (current.stateVersion() != expectedPreviousVersion) {
        throw new SwarmStateVersionConflictException(
            taskId, expectedPreviousVersion, current.stateVersion()
        );
    }
}
```

### 2.2 버전 관리 규칙

- `stateVersion = 0`: 신규 생성, 무조건 저장
- `stateVersion > 0`: 이전 버전 검증 후 저장
- 충돌 시: `SwarmStateVersionConflictException` 발생 → 재시도 필요

---

## 3. Circuit Breaker와 Swarm Cooldown 통합

### 3.1 두 메커니즘의 차이

| 항목 | Circuit Breaker | Swarm Cooldown |
|------|----------------|----------------|
| **적용 단계** | Invoke (호출 단계) | Plan (라우팅 계획 단계) |
| **동작 방식** | 연속 실패 시 호출 차단 (hard block) | 최근 실패 에이전트 우선순위 하락 (soft skip) |
| **차단 시간** | 30초 (설정 가능) | 120초 (기본값) |
| **설정 위치** | `host.a2a.circuit-breaker` | Swarm Coordinator 내부 |
| **목적** | 장애 전파 방지 | 최적 라우팅 선택 |

### 3.2 통합 시나리오

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 에이전트 A가 3회 연속 실패                                      │
└─────────────────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. Circuit Breaker가 30초간 open                             │
│    → invoke() 호출 시 즉시 CIRCUIT_OPEN 에러 반환                │
└─────────────────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. Swarm Coordinator가 실패 기록                              │
│    → agentCooldownUntilEpochMs 업데이트 (현재 + 120초)          │
└─────────────────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. 다음 요청 시 라우팅 계획 단계에서 에이전트 A 건너뜀                │
│    → 에이전트 B로 우회                                          │
└─────────────────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 5. 30초 후 Circuit Breaker 자동 복구                          │
│ 6. 120초 후 Swarm Cooldown 해제                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 SwarmState Facts 구조

```json
{
  "sharedFacts": {
    "agentCooldownUntilEpochMs": {
      "product": 1714694400000,
      "reservation": 1714694460000
    },
    "circuitBreakerOpenUntilEpochMs": {
      "product": 1714694370000
    },
    "lastInvokeFailedCount": 2,
    "lastInvokeSuccessCount": 1
  },
  "eventLog": [
    {
      "type": "INVOKE_BATCH_RECORDED",
      "at": "2026-04-12T10:00:00Z",
      "batchSize": 3,
      "failedCount": 2,
      "successCount": 1
    }
  ]
}
```

---

## 4. 라우팅 필터링 규칙

### 4.1 우선순위

`DefaultSupervisorSwarmCoordinator.applyRoutingRule()`는 다음 순서로 필터링합니다:

1. **Circuit Breaker open 확인** (우선순위 높음)
   - `circuitBreakerOpenUntilEpochMs` 확인
   - open 중이면 라우팅에서 제외

2. **Swarm Cooldown 확인**
   - `agentCooldownUntilEpochMs` 확인
   - cooldown 중이면 라우팅에서 제외

3. **강제 허용**
   - 모든 에이전트가 차단 중이면 첫 번째 계획 강제 허용
   - 무응답 방지

### 4.2 코드 예시

```java
@Override
public List<RoutingPlan> applyRoutingRule(
    String taskId,
    String sessionId,
    List<RoutingPlan> planned,
    Map<String, Object> swarmFacts
) {
    Map<String, Long> cooldown = cooldownMap(swarmFacts);
    Map<String, Long> circuitOpen = circuitBreakerMap(swarmFacts);

    long now = Instant.now().toEpochMilli();
    List<RoutingPlan> filtered = new ArrayList<>();

    for (RoutingPlan plan : planned) {
        // Circuit Breaker 확인 (우선순위 높음)
        if (circuitOpen.getOrDefault(plan.agentKey(), 0L) > now) {
            continue;  // 차단
        }

        // Swarm cooldown 확인
        if (cooldown.getOrDefault(plan.agentKey(), 0L) > now) {
            continue;  // 차단
        }

        filtered.add(plan);
    }

    // 모든 에이전트가 차단 중이면 강제 허용
    if (filtered.isEmpty() && !planned.isEmpty()) {
        return List.of(planned.get(0));
    }

    return filtered;
}
```

---

## 5. 이벤트 로그 관리

### 5.1 크기 제한

이벤트 로그는 최근 **100개**만 유지합니다 (메모리 누수 방지).

```java
// 이벤트 로그 크기 제한: 최근 MAX_EVENT_LOG_SIZE개만 유지
if (events.size() > MAX_EVENT_LOG_SIZE) {
    events.subList(0, events.size() - MAX_EVENT_LOG_SIZE).clear();
}
```

### 5.2 이벤트 타입

- `GRAPH_NODE_EVENT`: 그래프 노드 실행
- `INVOKE_BATCH_RECORDED`: 배치 호출 결과
- `PLAN`: 라우팅 계획 수립
- `SELECT`: 라우팅 선택
- `INVOKE`: 하위 에이전트 호출
- `MERGE`: 결과 병합
- `COMPOSE`: 응답 합성

---

## 6. 병렬 실행 및 예외 처리

### 6.1 병렬 실행 설정

```yaml
# a2a-supervisor.yml
host:
  a2a:
    execution:
      max-concurrency: 3  # 최대 동시 호출 수 (1이면 순차)
```

### 6.2 Resilience 패턴

병렬 실행 시 일부 실패를 허용합니다:

```java
private List<DownstreamCallResult> invokeBatch(
    List<RoutingPlan> batch,
    SupervisorPlanningContext context
) {
    List<CompletableFuture<DownstreamCallResult>> futures = batch.stream()
        .map(plan -> CompletableFuture.supplyAsync(
            () -> invocationService.invoke(plan, context))
            .exceptionally(error -> {
                // 예외 발생 시 실패 결과 객체 반환 (전체 배치 중단 방지)
                return new DownstreamCallResult(
                    plan.agentKey(),
                    context.getTaskId(),
                    "FAILED",
                    "",
                    "BATCH_INVOCATION_ERROR",
                    sanitize(error.getMessage())
                );
            }))
        .toList();
    return futures.stream().map(CompletableFuture::join).toList();
}
```

---

## 7. 운영 가이드

### 7.1 모니터링 지표

SwarmState를 통해 다음 지표를 추적할 수 있습니다:

- `lastInvokeFailedCount`: 마지막 배치의 실패 개수
- `lastInvokeSuccessCount`: 마지막 배치의 성공 개수
- `agentCooldownUntilEpochMs`: 에이전트별 cooldown 만료 시각
- `circuitBreakerOpenUntilEpochMs`: 에이전트별 circuit open 만료 시각

### 7.2 튜닝 파라미터

```java
// DefaultSupervisorSwarmCoordinator.java
private static final long FAILED_AGENT_COOLDOWN_MS = 120_000L;  // 2분
private static final int MAX_EVENT_LOG_SIZE = 100;

// InMemorySupervisorSwarmStateStore.java
private static final long MAX_TTL_MS = 3_600_000L;  // 1시간
private static final int MAX_ENTRIES_PER_MAP = 10_000;

// RedisSupervisorSwarmStateStore.java
private static final Duration DEFAULT_TTL = Duration.ofHours(1);
```

### 7.3 트러블슈팅

#### 문제: SwarmStateVersionConflictException 빈번 발생

- **원인**: 동시 요청이 많아 버전 충돌 발생
- **해결**:
  1. Redis 저장소 사용 (`spring.data.redis.enabled=true`)
  2. `max-concurrency` 값 조정

#### 문제: 메모리 누수

- **원인**: SwarmState가 계속 증가
- **해결**:
  1. TTL 확인 (기본 1시간)
  2. `MAX_ENTRIES_PER_MAP` 값 확인 (기본 10,000)
  3. Redis 저장소로 전환

#### 문제: 모든 에이전트가 차단되어 무응답

- **원인**: Circuit Breaker + Swarm Cooldown 동시 작동
- **해결**:
  - 자동으로 첫 번째 계획 강제 허용됨
  - Circuit Breaker 임계값 조정: `failure-threshold`
  - Cooldown 시간 조정: `FAILED_AGENT_COOLDOWN_MS`

---

## 8. 참고 자료

- [낙관적 락 vs 비관적 락](https://martinfowler.com/eaaCatalog/optimisticOfflineLock.html)
- [Circuit Breaker 패턴](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Redis Transactions](https://redis.io/topics/transactions)
- `16-reference-links.md`: 관련 참고 문서
- `29-supervisor-security-vulnerability-review-and-hardening-plan.md`: 보안 강화 계획
