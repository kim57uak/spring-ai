# 18. Supervisor Domain Pseudocode

## Request

```java
public record SupervisorAgentRequest(
        String sessionId,
        String message,
        String model
) {}
```

## Routing Plan

```java
public record RoutingPlan(
        String agentKey,
        String method,
        String reason,
        int priority,
        Map<String, Object> arguments,
        String sourceType,     // PLANNER | HANDOFF
        int handoffDepth,
        String parentAgentKey
) {}
```

## HITL Review

```java
public record HitlReviewContext(
        boolean required,
        String policyId,
        String decision, // APPROVE or CANCEL
        String reason,
        Instant expiresAt
) {}
```

## Downstream Result

```java
public record DownstreamCallResult(
        String agentKey,
        String taskId,
        String status,
        String payload,
        String errorCode,
        String errorMessage,
        boolean handoffRequested,
        String nextAgentKey,
        String handoffMethod,
        String handoffReason,
        Map<String, Object> handoffArguments
) {}
```

## Handoff Directive

```java
public record HandoffDirective(
        String nextAgentKey,
        String method,
        String reason,
        Map<String, Object> arguments
) {}
```

## Orchestrator Skeleton

```java
public Flux<String> execute(SupervisorAgentRequest request) {
    SupervisorPlanningContext context = loadContext(request);
    List<RoutingPlan> plans = planningService.plan(context);
    HitlReviewContext review = hitlPolicyService.evaluate(context, plans);
    if (review.required()) {
        HitlReviewContext resolved = hitlDecisionService.awaitDecision(context.getSessionId());
        if ("CANCEL".equals(resolved.decision())) {
            persistCanceled(context, resolved);
            return Flux.just("요청이 검토 단계에서 취소되었습니다.");
        }
    }
    for (RoutingPlan plan : bounded(plans)) {
        DownstreamCallResult result = invocationService.invoke(plan, context);
        context.addResult(result);
        if (handoffEnabled() && result.handoffRequested()) {
            HandoffValidationResult checked = handoffPolicyService.evaluate(result, context);
            if (checked.accepted()) {
                context.addPlan(toHandoffPlan(result, checked)); // sourceType=HANDOFF
            }
        }
        progressEmitter.emit(
                SupervisorProgressSupport.line("handoff", 65, "handoff evaluated", Map.of(
                        "enabled", handoffEnabled(),
                        "fromAgent", result.agentKey(),
                        "toAgent", result.nextAgentKey()
                ))
        );
    }
    return composeService.streamCompose(context)
            .doFinally(signal -> persist(context));
}
```

## Swarm State Skeleton

```java
public record SwarmSharedState(
        long stateVersion,
        Map<String, Object> sharedFacts,
        List<Map<String, Object>> eventLog
) {}
```

`sharedFacts` 확장 키(예시):
- `handoffHopCount`
- `handoffPath`
- `handoffBlockedCount`
- `lastHandoffAgent`
- `lastHandoffAt`


---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.

---

## 2026-04-13 동기화 메모 (34 반영)

- handoff는 feature flag(`handoff.enabled`)로 on/off하며 OFF 시 directive를 skip 처리한다.
- handoff method는 기존 허용 enum만 사용하고 stream 미지원 agent로의 stream handoff는 금지한다.
- 진행상태/생각과정 출력은 `SupervisorProgressSupport` 공통 포맷을 사용한다.
