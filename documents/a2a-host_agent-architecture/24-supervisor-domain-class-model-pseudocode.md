# 24. Supervisor Domain Class Model Pseudocode

```java
public record SupervisorAgentRequest(
        String sessionId,
        String message,
        String model
) {}
```

```java
public record RoutingPlan(
        String agentKey,
        String method,
        String reason,
        int priority,
        Map<String, Object> arguments
) {}
```

```java
public record DownstreamCallResult(
        String agentKey,
        String taskId,
        String status,
        String payload,
        String errorCode,
        String errorMessage
) {}
```

```java
public record HitlReviewContext(
        boolean required,
        String policyId,
        String decision, // APPROVE or CANCEL
        String reason
) {}
```

```java
public record SwarmSharedState(
        long stateVersion,
        Map<String, Object> sharedFacts,
        List<Map<String, Object>> eventLog
) {}
```

```java
public interface A2AInvocationService {
    DownstreamCallResult invoke(RoutingPlan plan, SupervisorPlanningContext context);
}
```

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
