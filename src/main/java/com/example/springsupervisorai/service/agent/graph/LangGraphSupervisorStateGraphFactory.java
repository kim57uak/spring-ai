package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorGraphRoute;
import com.example.springsupervisorai.model.SupervisorGraphNode;
import com.example.springsupervisorai.model.SupervisorGraphState;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.model.SupervisorRuntimeState;
import com.example.springsupervisorai.service.SupervisorProgressSupport;
import com.example.springsupervisorai.service.agent.handoff.HandoffPolicyService;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.plan.SupervisorPlanningService;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Supervisor 오케스트레이션을 LangGraph 상태 머신으로 구성하는 팩토리 구현체.
 * <p>
 * 그래프 책임:
 * - plan: LLM 기반 라우팅 계획 생성
 * - select: 다음 호출 대상 선택
 * - invoke: A2A downstream 호출 실행
 * - merge: 호출 결과 상태 반영
 * - compose: 최종 응답 합성 단계 진입
 */
@Component
public class LangGraphSupervisorStateGraphFactory implements SupervisorStateGraphFactory {

    private static final String START = "__START__";
    private static final String END = "__END__";

    private static final String PLAN_NODE = SupervisorGraphNode.PLAN.nodeId();
    private static final String SELECT_NODE = SupervisorGraphNode.SELECT.nodeId();
    private static final String INVOKE_NODE = SupervisorGraphNode.INVOKE.nodeId();
    private static final String HANDOFF_EVALUATE_NODE = SupervisorGraphNode.HANDOFF_EVALUATE.nodeId();
    private static final String HANDOFF_APPLY_NODE = SupervisorGraphNode.HANDOFF_APPLY.nodeId();
    private static final String MERGE_NODE = SupervisorGraphNode.MERGE.nodeId();
    private static final String COMPOSE_NODE = SupervisorGraphNode.COMPOSE.nodeId();

    private static final int MAX_ITERATIONS = 5;

    private final CompiledGraph<SupervisorGraphState> compiledGraph;
    private final SupervisorPlanningService planningService;
    private final A2AInvocationService invocationService;
    private final HandoffPolicyService handoffPolicyService;
    private final A2aSupervisorRoutingProperties routingProperties;
    private final SupervisorSwarmCoordinator swarmCoordinator;

    /**
     * 그래프 의존성을 주입받아 컴파일된 그래프를 초기화한다.
     *
     * @param planningService 라우팅 계획 서비스
     * @param invocationService downstream 호출 서비스
     * @throws GraphStateException 그래프 구성 규칙 위반 시
     */
    public LangGraphSupervisorStateGraphFactory(
            SupervisorPlanningService planningService,
            A2AInvocationService invocationService,
            HandoffPolicyService handoffPolicyService,
            A2aSupervisorRoutingProperties routingProperties,
            SupervisorSwarmCoordinator swarmCoordinator
    ) throws GraphStateException {
        this.planningService = planningService;
        this.invocationService = invocationService;
        this.handoffPolicyService = handoffPolicyService;
        this.routingProperties = routingProperties;
        this.swarmCoordinator = swarmCoordinator;
        this.compiledGraph = buildGraph().compile();
    }

    /**
     * 컴파일된 Supervisor 상태 그래프를 반환한다.
     *
     * @return 재사용 가능한 compiled graph 인스턴스
     */
    @Override
    public CompiledGraph<SupervisorGraphState> getCompiledGraph() {
        return compiledGraph;
    }

    /**
     * Supervisor 그래프 구조를 생성한다.
     *
     * @return 아직 compile되지 않은 그래프 정의
     * @throws GraphStateException 노드/엣지 구성 오류 시
     */
    private StateGraph<SupervisorGraphState> buildGraph() throws GraphStateException {
        StateGraph<SupervisorGraphState> graph = new StateGraph<>(SupervisorGraphState::new);

        graph.addNode(PLAN_NODE, planNode());
        graph.addNode(SELECT_NODE, selectNode());
        graph.addNode(INVOKE_NODE, invokeNode());
        graph.addNode(HANDOFF_EVALUATE_NODE, handoffEvaluateNode());
        graph.addNode(HANDOFF_APPLY_NODE, handoffApplyNode());
        graph.addNode(MERGE_NODE, mergeNode());
        graph.addNode(COMPOSE_NODE, composeNode());

        graph.addEdge(START, PLAN_NODE);
        graph.addEdge(PLAN_NODE, SELECT_NODE);
        graph.addConditionalEdges(SELECT_NODE, routeAfterSelect(), Map.of(
                SupervisorGraphRoute.INVOKE.value(), INVOKE_NODE,
                SupervisorGraphRoute.COMPOSE.value(), COMPOSE_NODE
        ));
        graph.addEdge(INVOKE_NODE, HANDOFF_EVALUATE_NODE);
        graph.addEdge(HANDOFF_EVALUATE_NODE, HANDOFF_APPLY_NODE);
        graph.addEdge(HANDOFF_APPLY_NODE, MERGE_NODE);
        graph.addEdge(MERGE_NODE, SELECT_NODE);
        graph.addEdge(COMPOSE_NODE, END);
        return graph;
    }

    /**
     * PLAN 노드 액션을 생성한다.
     * <p>
     * 입력 컨텍스트를 기반으로 라우팅 계획 목록을 계산하고
     * 그래프 상태에 계획/인덱스/결과 초기값을 기록한다.
     *
     * @return PLAN 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> planNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            List<RoutingPlan> planned = planningService.plan(context);
            List<RoutingPlan> plans = swarmCoordinator.applyRoutingRule(
                    context.getTaskId(),
                    context.getSessionId(),
                    planned,
                    context.getSwarmSharedFacts()
            );
            swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "PLAN", "Routing plans created", Map.of(
                    "plannedCount", planned.size(),
                    "filteredCount", plans.size(),
                    "swarmStateVersion", context.getSwarmStateVersion()
            ));
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.PLANNED.value());
            updates.put(SupervisorGraphState.ROUTING_INDEX, 0);
            updates.put(SupervisorGraphState.ROUTING_PLANS, toPlanList(plans));
            updates.put(SupervisorGraphState.DOWNSTREAM_RESULTS, List.of());
            return CompletableFuture.completedFuture(updates);
        };
    }

    /**
     * SELECT 노드 액션을 생성한다.
     * <p>
     * 현재 라우팅 인덱스 기준으로 다음 실행 대상 계획을 선택해
     * CURRENT_PLAN 상태 필드에 반영한다.
     *
     * @return SELECT 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> selectNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            int index = Math.max(0, context.getRoutingIndex());
            List<RoutingPlan> plans = context.getRoutingPlans();
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.ROUTING_SELECTED.value());

            if (index >= MAX_ITERATIONS || index >= plans.size()) {
                updates.put(SupervisorGraphState.CURRENT_PLAN, Map.of());
                swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "SELECT", "No further plan to invoke", Map.of(
                        "routingIndex", index,
                        "planCount", plans.size()
                ));
            } else {
                updates.put(SupervisorGraphState.CURRENT_PLAN, toPlanMap(plans.get(index)));
                swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "SELECT", "Current plan selected", Map.of(
                        "routingIndex", index,
                        "agentKey", plans.get(index).agentKey()
                ));
            }
            return CompletableFuture.completedFuture(updates);
        };
    }

    /**
     * INVOKE 노드 액션을 생성한다.
     * <p>
     * 현재 라우팅 계획을 downstream A2A 경계로 호출하고,
     * 반환 결과를 누적 결과 목록에 병합한다.
     *
     * @return INVOKE 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> invokeNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            int fromIndex = Math.max(0, context.getRoutingIndex());
            int maxConcurrency = normalizedConcurrency();
            List<RoutingPlan> batch = resolveBatch(context, fromIndex, maxConcurrency);
            if (batch.isEmpty()) {
                return CompletableFuture.completedFuture(Map.of(
                        SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.A2A_CALLING.value(),
                        SupervisorGraphState.ROUTING_INDEX, fromIndex
                ));
            }
            swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "INVOKE", "Invoking downstream batch", Map.of(
                    "fromIndex", fromIndex,
                    "batchSize", batch.size(),
                    "maxConcurrency", maxConcurrency
            ));
            List<DownstreamCallResult> batchResults = invokeBatch(batch, context);
            swarmCoordinator.recordInvocationBatch(context.getTaskId(), context.getSessionId(), batchResults);

            List<DownstreamCallResult> mergedResults = new ArrayList<>(context.getResults());
            mergedResults.addAll(batchResults);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.A2A_CALLING.value());
            updates.put(SupervisorGraphState.DOWNSTREAM_RESULTS, toResultList(mergedResults));
            updates.put(SupervisorGraphState.LAST_INVOKE_BATCH_RESULTS, toResultList(batchResults));
            updates.put(SupervisorGraphState.ROUTING_INDEX, fromIndex + batch.size());
            return CompletableFuture.completedFuture(updates);
        };
    }

    /**
     * HANDOFF_EVALUATE 노드 액션을 생성한다.
     * <p>
     * 직전 invoke 배치 결과에서 handoff 지시를 추출/검증하고 결과를 상태에 저장한다.
     *
     * @return HANDOFF_EVALUATE 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> handoffEvaluateNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            List<DownstreamCallResult> batchResults = readResults(state.value(SupervisorGraphState.LAST_INVOKE_BATCH_RESULTS).orElse(List.of()));
            List<HandoffValidationResult> validations = handoffPolicyService.evaluate(context, batchResults);
            boolean handoffEnabled = routingProperties.getHandoff() != null && routingProperties.getHandoff().isEnabled();

            long acceptedCount = validations.stream().filter(HandoffValidationResult::accepted).count();
            long skippedByFlag = validations.stream()
                    .filter(result -> !result.accepted() && "FLAG_DISABLED".equals(result.reasonCode()))
                    .count();
            swarmCoordinator.recordHandoffEvaluations(context.getTaskId(), context.getSessionId(), validations, handoffEnabled);

            swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "HANDOFF_EVALUATE", "Handoff directives evaluated", Map.of(
                    "stage", SupervisorProgressSupport.STAGE_HANDOFF,
                    "progress", 60,
                    "batchResultCount", batchResults.size(),
                    "validationCount", validations.size(),
                    "acceptedCount", acceptedCount,
                    "skippedByFlag", skippedByFlag,
                    "handoffEnabled", handoffEnabled
            ));

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.HANDOFF_EVALUATING.value());
            updates.put(SupervisorGraphState.HANDOFF_VALIDATIONS, toHandoffValidationList(validations));
            updates.put(SupervisorGraphState.HANDOFF_ENABLED, handoffEnabled);
            return CompletableFuture.completedFuture(updates);
        };
    }

    /**
     * HANDOFF_APPLY 노드 액션을 생성한다.
     * <p>
     * 검증을 통과한 handoff plan을 routing queue에 동적으로 삽입한다.
     * feature flag 비활성/정책 차단 시에는 기존 queue를 유지한다.
     *
     * @return HANDOFF_APPLY 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> handoffApplyNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            List<HandoffValidationResult> validations = readHandoffValidations(state.value(SupervisorGraphState.HANDOFF_VALIDATIONS).orElse(List.of()));
            List<RoutingPlan> updatedPlans = new ArrayList<>(context.getRoutingPlans());
            int insertIndex = Math.min(Math.max(0, context.getRoutingIndex()), updatedPlans.size());
            int appliedCount = 0;
            int skippedCount = 0;

            for (HandoffValidationResult validation : validations) {
                if (!validation.accepted() || validation.plan() == null) {
                    skippedCount++;
                    swarmCoordinator.recordNodeEvent(
                            context.getTaskId(),
                            context.getSessionId(),
                            "HANDOFF_APPLY",
                            "Handoff validation rejected, fallback to original plan",
                            rejectedHandoffMetadata(validation)
                    );
                    continue;
                }
                updatedPlans.add(insertIndex++, validation.plan());
                appliedCount++;
            }

            String stage = appliedCount > 0
                    ? SupervisorProgressSupport.STAGE_HANDOFF_APPLIED
                    : SupervisorProgressSupport.STAGE_HANDOFF_SKIPPED;
            int progress = appliedCount > 0 ? 65 : 62;
            swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "HANDOFF_APPLY", "Handoff plans applied", Map.of(
                    "stage", stage,
                    "progress", progress,
                    "appliedCount", appliedCount,
                    "skippedCount", skippedCount,
                    "routingPlanCount", updatedPlans.size()
            ));

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(SupervisorGraphState.CURRENT_NODE, appliedCount > 0
                    ? SupervisorRuntimeState.HANDOFF_APPLIED.value()
                    : SupervisorRuntimeState.HANDOFF_SKIPPED.value());
            updates.put(SupervisorGraphState.ROUTING_PLANS, toPlanList(updatedPlans));
            return CompletableFuture.completedFuture(updates);
        };
    }

    /**
     * MERGE 노드 액션을 생성한다.
     * <p>
     * 실제 병합 데이터는 invoke 노드에서 이미 반영되므로
     * 본 노드는 현재 상태 마커만 갱신한다.
     *
     * @return MERGE 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> mergeNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "MERGE", "Downstream results merged", Map.of(
                    "resultsCount", context.getResults().size()
            ));
            return CompletableFuture.completedFuture(Map.of(
                    SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.A2A_RESULT_MERGED.value()
            ));
        };
    }

    /**
     * COMPOSE 노드 액션을 생성한다.
     * <p>
     * 최종 응답 합성 단계로 진입했음을 상태에 표시한다.
     *
     * @return COMPOSE 노드 비동기 액션
     */
    private AsyncNodeAction<SupervisorGraphState> composeNode() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            swarmCoordinator.recordNodeEvent(context.getTaskId(), context.getSessionId(), "COMPOSE", "Compose node entered", Map.of(
                    "resultsCount", context.getResults().size()
            ));
            return CompletableFuture.completedFuture(Map.of(
                    SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.COMPOSING.value()
            ));
        };
    }

    /**
     * SELECT 이후 분기 규칙을 제공한다.
     * <p>
     * 남은 실행 계획이 있으면 INVOKE로, 없으면 COMPOSE로 이동한다.
     *
     * @return 조건부 엣지 분기 액션
     */
    private AsyncEdgeAction<SupervisorGraphState> routeAfterSelect() {
        return state -> {
            SupervisorPlanningContext context = state.toPlanningContext();
            boolean hasNextPlan = context.getRoutingIndex() < MAX_ITERATIONS
                    && context.getRoutingIndex() < context.getRoutingPlans().size();
            return CompletableFuture.completedFuture(
                    hasNextPlan ? SupervisorGraphRoute.INVOKE.value() : SupervisorGraphRoute.COMPOSE.value()
            );
        };
    }

    /**
     * 현재 인덱스에서 최대 동시 실행 개수만큼 실행 배치를 구성한다.
     */
    private List<RoutingPlan> resolveBatch(SupervisorPlanningContext context, int fromIndex, int maxConcurrency) {
        if (fromIndex >= context.getRoutingPlans().size() || fromIndex >= MAX_ITERATIONS) {
            return List.of();
        }
        int upper = Math.min(context.getRoutingPlans().size(), MAX_ITERATIONS);
        int toIndex = Math.min(upper, fromIndex + Math.max(1, maxConcurrency));
        if (fromIndex >= toIndex) {
            return List.of();
        }
        return context.getRoutingPlans().subList(fromIndex, toIndex);
    }

    /**
     * 실행 배치를 호출한다.
     * <p>
     * - 배치 1건은 순차 호출
     * - 배치 2건 이상은 CompletableFuture 기반 병렬 호출
     * - 일부 실패는 허용하고 성공한 결과만 반환 (resilience 패턴)
     */
    private List<DownstreamCallResult> invokeBatch(List<RoutingPlan> batch, SupervisorPlanningContext context) {
        if (batch.size() == 1) {
            return List.of(invocationService.invoke(batch.get(0), context));
        }
        List<CompletableFuture<DownstreamCallResult>> futures = batch.stream()
                .map(plan -> CompletableFuture.supplyAsync(() -> invocationService.invoke(plan, context))
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

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private int normalizedConcurrency() {
        A2aSupervisorRoutingProperties.Execution execution = routingProperties.getExecution();
        if (execution == null) {
            return 1;
        }
        return Math.max(1, execution.getMaxConcurrency());
    }

    /**
     * 도메인 라우팅 계획 목록을 그래프 상태 저장용 맵 목록으로 변환한다.
     *
     * @param plans 도메인 계획 목록
     * @return 직렬화 친화적 맵 목록
     */
    private List<Map<String, Object>> toPlanList(List<RoutingPlan> plans) {
        return plans.stream().map(this::toPlanMap).toList();
    }

    /**
     * 단일 라우팅 계획을 상태 저장용 맵으로 변환한다.
     *
     * @param plan 변환 대상 계획
     * @return plan 직렬화 맵
     */
    private Map<String, Object> toPlanMap(RoutingPlan plan) {
        return Map.of(
                "agentKey", plan.agentKey(),
                "method", plan.method(),
                "reason", plan.reason(),
                "priority", plan.priority(),
                "arguments", plan.arguments() == null ? Map.of() : plan.arguments(),
                "sourceType", safe(plan.sourceType()),
                "handoffDepth", plan.handoffDepth(),
                "parentAgentKey", safe(plan.parentAgentKey())
        );
    }

    /**
     * downstream 호출 결과 목록을 상태 저장용 맵 목록으로 변환한다.
     *
     * @param results downstream 결과 목록
     * @return 결과 직렬화 맵 목록
     */
    private List<Map<String, Object>> toResultList(List<DownstreamCallResult> results) {
        return results.stream()
                .map(result -> {
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("agentKey", safe(result.agentKey()));
                    mapped.put("taskId", safe(result.taskId()));
                    mapped.put("status", safe(result.status()));
                    mapped.put("payload", safe(result.payload()));
                    mapped.put("errorCode", safe(result.errorCode()));
                    mapped.put("errorMessage", safe(result.errorMessage()));
                    mapped.put("handoffRequested", result.handoffRequested());
                    mapped.put("nextAgentKey", safe(result.nextAgentKey()));
                    mapped.put("handoffMethod", safe(result.handoffMethod()));
                    mapped.put("handoffReason", safe(result.handoffReason()));
                    mapped.put("handoffArguments", result.handoffArguments() == null ? Map.of() : result.handoffArguments());
                    return mapped;
                })
                .toList();
    }

    private List<Map<String, Object>> toHandoffValidationList(List<HandoffValidationResult> validations) {
        if (validations == null || validations.isEmpty()) {
            return List.of();
        }
        return validations.stream().map(validation -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("accepted", validation.accepted());
            mapped.put("reasonCode", safe(validation.reasonCode()));
            mapped.put("hopCount", validation.hopCount());

            HandoffDirective directive = validation.directive();
            if (directive != null) {
                mapped.put("fromAgentKey", safe(directive.fromAgentKey()));
                mapped.put("nextAgentKey", safe(directive.nextAgentKey()));
                mapped.put("method", safe(directive.method()));
                mapped.put("reason", safe(directive.reason()));
                mapped.put("arguments", directive.arguments() == null ? Map.of() : directive.arguments());
            } else {
                mapped.put("fromAgentKey", "");
                mapped.put("nextAgentKey", "");
                mapped.put("method", "");
                mapped.put("reason", "");
                mapped.put("arguments", Map.of());
            }

            RoutingPlan plan = validation.plan();
            mapped.put("plan", plan == null ? Map.of() : toPlanMap(plan));
            return mapped;
        }).toList();
    }

    /**
     * 검증 실패 handoff의 fallback 사유를 이벤트 메타데이터로 구성한다.
     *
     * @param validation handoff 검증 결과
     * @return fallback 분석용 메타데이터
     */
    private Map<String, Object> rejectedHandoffMetadata(HandoffValidationResult validation) {
        HandoffDirective directive = validation == null ? null : validation.directive();
        return Map.of(
                "stage", SupervisorProgressSupport.STAGE_HANDOFF_SKIPPED,
                "progress", 62,
                "fallback", true,
                "reasonCode", validation == null ? "" : safe(validation.reasonCode()),
                "fromAgent", directive == null ? "" : safe(directive.fromAgentKey()),
                "toAgent", directive == null ? "" : safe(directive.nextAgentKey()),
                "reason", directive == null ? "" : safe(directive.reason()),
                "hopCount", validation == null ? 0 : validation.hopCount()
        );
    }

    @SuppressWarnings("unchecked")
    private List<DownstreamCallResult> readResults(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        ArrayList<DownstreamCallResult> converted = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            converted.add(new DownstreamCallResult(
                    readString(map, "agentKey"),
                    readString(map, "taskId"),
                    readString(map, "status"),
                    readString(map, "payload"),
                    readString(map, "errorCode"),
                    readString(map, "errorMessage"),
                    readBoolean(map, "handoffRequested"),
                    readString(map, "nextAgentKey"),
                    readString(map, "handoffMethod"),
                    readString(map, "handoffReason"),
                    readMap(map, "handoffArguments")
            ));
        }
        return converted;
    }

    @SuppressWarnings("unchecked")
    private List<HandoffValidationResult> readHandoffValidations(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        ArrayList<HandoffValidationResult> converted = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            HandoffDirective directive = new HandoffDirective(
                    readString(map, "fromAgentKey"),
                    readString(map, "nextAgentKey"),
                    readString(map, "method"),
                    readString(map, "reason"),
                    readMap(map, "arguments")
            );
            Map<String, Object> planMap = readMap(map, "plan");
            RoutingPlan plan = planMap.isEmpty() ? null : new RoutingPlan(
                    readString(planMap, "agentKey"),
                    readString(planMap, "method"),
                    readString(planMap, "reason"),
                    readInt(planMap, "priority"),
                    readMap(planMap, "arguments"),
                    readString(planMap, "sourceType"),
                    readInt(planMap, "handoffDepth"),
                    readString(planMap, "parentAgentKey")
            );
            converted.add(new HandoffValidationResult(
                    readBoolean(map, "accepted"),
                    readString(map, "reasonCode"),
                    directive,
                    plan,
                    readInt(map, "hopCount")
            ));
        }
        return converted;
    }

    /**
     * null-safe 문자열 정규화 유틸리티.
     *
     * @param value 원본 문자열
     * @return null이면 빈 문자열, 아니면 원본 값
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 맵에서 문자열 값을 추출한다.
     *
     * @param map 조회 대상 맵
     * @param key 조회 키
     * @return 키가 없으면 빈 문자열
     */
    private String readString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 맵에서 정수 값을 추출한다.
     *
     * @param map 조회 대상 맵
     * @param key 조회 키
     * @return 파싱 실패 시 0
     */
    private int readInt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean readBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * 맵에서 하위 arguments 맵을 안전하게 추출한다.
     *
     * @param map 상위 맵
     * @param key 하위 맵 키
     * @return 변환된 arguments 맵(없으면 빈 맵)
     */
    private Map<String, Object> readMap(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        source.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }
}
