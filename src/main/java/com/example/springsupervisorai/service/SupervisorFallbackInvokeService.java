package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.graph.SupervisorBatchExecutionPolicy;
import com.example.springsupervisorai.service.agent.graph.SupervisorPlanRunner;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 그래프 결과가 비어 있을 때 downstream fallback invoke를 수행한다.
 */
@Service
public class SupervisorFallbackInvokeService {

    @FunctionalInterface
    public interface ProgressCallback {
        void emit(String stage, int progress, String message, Map<String, Object> metadata);
    }

    private final SupervisorBatchExecutionPolicy batchExecutionPolicy;
    private final SupervisorPlanRunner planRunner;
    private final SupervisorSwarmCoordinator swarmCoordinator;

    /**
     * 공유 실행 정책과 실행기를 주입받아 fallback invoke를 구성한다.
     *
     * @param batchExecutionPolicy graph/fallback 공용 batch 선택 정책
     * @param planRunner graph/fallback 공용 plan 실행기
     * @param swarmCoordinator downstream 실행 결과를 swarm 상태에 기록하는 coordinator
     */
    public SupervisorFallbackInvokeService(
            SupervisorBatchExecutionPolicy batchExecutionPolicy,
            SupervisorPlanRunner planRunner,
            SupervisorSwarmCoordinator swarmCoordinator
    ) {
        this.batchExecutionPolicy = batchExecutionPolicy;
        this.planRunner = planRunner;
        this.swarmCoordinator = swarmCoordinator;
    }

    /**
     * 그래프 결과가 비어 있으면 공용 batch 정책에 따라 fallback downstream invoke를 수행한다.
     *
     * @param request supervisor 요청
     * @param taskId supervisor task id
     * @param canceled 취소 플래그
     * @param context planning context
     * @param progressCallback progress emitter
     * @param cancellationChecker task 취소 확인기
     */
    public void invokeIfRequired(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            SupervisorPlanningContext context,
            ProgressCallback progressCallback,
            BooleanSupplier cancellationChecker
    ) {
        if (!context.getResults().isEmpty() || context.getRoutingPlans().isEmpty()) {
            return;
        }

        int maxIterations = Math.min(batchExecutionPolicy.maxIterations(), context.getRoutingPlans().size());
        progressCallback.emit(SupervisorProgressSupport.STAGE_INVOKING, 60,
                "→ INVOKE 노드 수동 실행: 하위 에이전트 호출 시작",
                Map.of(
                        "nodeType", "INVOKE",
                        "executionMode", "manual(fallback)",
                        "totalCalls", maxIterations
                ));

        int fromIndex = Math.max(0, context.getRoutingIndex());
        while (fromIndex < maxIterations) {
            if (canceled.get() || cancellationChecker.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Supervisor task canceled");
            }
            List<RoutingPlan> batch = batchExecutionPolicy.resolveBatch(context, fromIndex);
            if (batch.isEmpty()) {
                break;
            }
            RoutingPlan firstPlan = batch.get(0);
            int currentProgress = 60 + (fromIndex * 15 / maxIterations);

            progressCallback.emit(SupervisorProgressSupport.STAGE_INVOKING, currentProgress,
                    "🔄 하위 에이전트 호출 #" + (fromIndex + 1) + "/" + maxIterations + ": " + firstPlan.agentKey(),
                    Map.of(
                            "callIndex", fromIndex + 1,
                            "totalCalls", maxIterations,
                            "batchSize", batch.size(),
                            "agentKey", firstPlan.agentKey(),
                            "method", firstPlan.method(),
                            "endpoint", "/a2a/" + firstPlan.agentKey() + "/" + firstPlan.method(),
                            "arguments", summarizeArguments(firstPlan.arguments())
                    ));

            List<DownstreamCallResult> batchResults = planRunner.invokeBatch(batch, context);
            batchResults.forEach(context::addResult);
            swarmCoordinator.recordInvocationBatch(taskId, request.sessionId(), batchResults);

            for (int batchOffset = 0; batchOffset < batchResults.size(); batchOffset++) {
                DownstreamCallResult result = batchResults.get(batchOffset);
                progressCallback.emit(SupervisorProgressSupport.STAGE_INVOKING, currentProgress + 3,
                        "✓ 호출 완료 #" + (fromIndex + batchOffset + 1) + ": " + result.agentKey() + " → " + result.status(),
                        Map.of(
                                "callIndex", fromIndex + batchOffset + 1,
                                "agentKey", result.agentKey(),
                                "status", result.status(),
                                "errorCode", safe(result.errorCode()),
                                "payloadLength", result.payload() == null ? 0 : result.payload().length(),
                                "hasError", result.errorCode() != null && !result.errorCode().isBlank()
                        ));
            }
            fromIndex += batch.size();
        }
        context.setRoutingIndex(fromIndex);

        progressCallback.emit(SupervisorProgressSupport.STAGE_INVOKING, 75,
                "✓ INVOKE 노드 완료 (수동 실행)",
                Map.of(
                        "nodeType", "INVOKE",
                        "executionMode", "manual(fallback)",
                        "resultsCount", context.getResults().size()
                ));
    }

    private String summarizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        String raw = String.valueOf(arguments);
        if (raw.length() > 180) {
            return raw.substring(0, 180) + "...";
        }
        return raw;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
