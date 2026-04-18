package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 그래프 실행 후 요약/진행 메시지 방출을 담당한다.
 * <p>
 * 오케스트레이터에서 라우팅 계획 요약, handoff 요약, 그래프 실행 결과 로그를 분리해
 * pipeline coordination과 요약 출력 책임을 분리한다.
 */
@Service
public class SupervisorExecutionSummaryEmitter {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorExecutionSummaryEmitter.class);

    /**
     * 오케스트레이터가 진행 이벤트를 위임할 때 사용하는 콜백.
     */
    @FunctionalInterface
    public interface ProgressReporter {
        void emit(String stage, int progress, String message, Map<String, Object> metadata);
    }

    private final SupervisorHandoffProgressSupport handoffProgressSupport;

    public SupervisorExecutionSummaryEmitter(SupervisorHandoffProgressSupport handoffProgressSupport) {
        this.handoffProgressSupport = handoffProgressSupport;
    }

    /**
     * 그래프 실행 완료 직후 핵심 요약 이벤트를 방출한다.
     *
     * @param context planning context
     * @param snapshot typed graph snapshot
     * @param reporter 진행 이벤트 reporter
     */
    public void emitGraphCompletion(
            SupervisorPlanningContext context,
            SupervisorGraphSnapshot snapshot,
            ProgressReporter reporter
    ) {
        reporter.emit(SupervisorProgressSupport.STAGE_GRAPH, 38, "✓ 그래프 실행 완료. 노드별 결과를 확인합니다...", Map.of(
                "finalNode", safe(context.getCurrentNode()),
                "planCount", context.getRoutingPlans().size(),
                "resultsCount", context.getResults().size()
        ));

        if (!context.getRoutingPlans().isEmpty()) {
            reporter.emit(SupervisorProgressSupport.STAGE_GRAPH, 39, "✓ PLAN 노드 실행 완료", Map.of(
                    "nodeType", "PLAN",
                    "output", context.getRoutingPlans().size() + "개의 라우팅 계획 생성",
                    "agents", context.getRoutingPlans().stream().map(RoutingPlan::agentKey).toList().toString()
            ));
        }

        if (!context.getResults().isEmpty()) {
            reporter.emit(SupervisorProgressSupport.STAGE_GRAPH, 39, "✓ INVOKE 노드 실행 완료 (그래프 내부)", Map.of(
                    "nodeType", "INVOKE",
                    "output", context.getResults().size() + "개의 하위 에이전트 호출 결과",
                    "results", context.getResults().stream().map(r -> r.agentKey() + ":" + r.status()).toList().toString()
            ));
        }

        long handoffPlanCount = context.getRoutingPlans().stream().filter(RoutingPlan::isHandoff).count();
        Map<String, Object> handoffMetadata = handoffProgressSupport.metadata(
                snapshot,
                handoffPlanCount,
                context.getRoutingPlans().size()
        );
        if (handoffPlanCount > 0) {
            reporter.emit(SupervisorProgressSupport.STAGE_HANDOFF_APPLIED, 40, "handoff 계획이 반영되었습니다.", handoffMetadata);
        } else {
            reporter.emit(SupervisorProgressSupport.STAGE_HANDOFF_SKIPPED, 40, "handoff 적용 없이 기본 라우팅을 유지합니다.", handoffMetadata);
        }

        reporter.emit(SupervisorProgressSupport.STAGE_SWARM, 41, "Swarm 라우팅 반영 완료", Map.of(
                "swarmStateVersion", context.getSwarmStateVersion(),
                "finalPlanCount", context.getRoutingPlans().size()
        ));
    }

    /**
     * 그래프가 계산한 라우팅 계획을 로그/진행 이벤트로 출력한다.
     *
     * @param request supervisor 요청
     * @param context planning context
     * @param sanitizer 로그 노출용 reason 정규화 함수
     * @param reporter 진행 이벤트 reporter
     */
    public void emitRoutingPlanDetails(
            SupervisorAgentRequest request,
            SupervisorPlanningContext context,
            Function<String, String> sanitizer,
            ProgressReporter reporter
    ) {
        logger.info("Supervisor planning result sessionId={}, planCount={}, plans={}",
                request.sessionId(), context.getRoutingPlans().size(),
                context.getRoutingPlans().stream().map(plan -> plan.agentKey() + ":" + plan.method()).toList());

        reporter.emit(SupervisorProgressSupport.STAGE_PLANNING, 40, "라우팅 계획이 수립되었습니다.", Map.of(
                "planCount", context.getRoutingPlans().size()
        ));

        int planIndex = 0;
        for (RoutingPlan plan : context.getRoutingPlans()) {
            logger.info(
                    "Supervisor selected downstream sessionId={}, agentKey={}, method={}, priority={}, reason={}, argumentKeys={}",
                    request.sessionId(),
                    plan.agentKey(),
                    plan.method(),
                    plan.priority(),
                    sanitizer.apply(plan.reason()),
                    plan.arguments() == null ? List.of() : plan.arguments().keySet()
            );
            reporter.emit(SupervisorProgressSupport.STAGE_ROUTING, 50 + (planIndex * 2),
                    "📋 라우팅 계획 #" + (planIndex + 1) + ": " + plan.agentKey() + " → " + plan.method(),
                    Map.of(
                            "planIndex", planIndex + 1,
                            "agentKey", plan.agentKey(),
                            "method", plan.method(),
                            "priority", plan.priority(),
                            "reason", sanitizer.apply(plan.reason()),
                            "arguments", summarizeArguments(plan.arguments())
                    ));
            planIndex++;
        }
    }

    /**
     * 그래프 내부에서 이미 수행된 downstream 결과를 로그/진행 이벤트로 출력한다.
     *
     * @param request supervisor 요청
     * @param context planning context
     * @param reporter 진행 이벤트 reporter
     */
    public void emitGraphInvocationSummary(
            SupervisorAgentRequest request,
            SupervisorPlanningContext context,
            ProgressReporter reporter
    ) {
        if (context.getResults().isEmpty()) {
            return;
        }

        logger.info("Supervisor graph downstream aggregation sessionId={}, resultsCount={}",
                request.sessionId(), context.getResults().size());
        for (DownstreamCallResult result : context.getResults()) {
            logger.info("Supervisor graph downstream result sessionId={}, {}",
                    request.sessionId(), summarizeResult(result));
        }

        reporter.emit(SupervisorProgressSupport.STAGE_INVOKING, 60, "그래프 내에서 하위 에이전트가 이미 실행되었습니다. 결과를 확인합니다.", Map.of(
                "resultsCount", context.getResults().size(),
                "executedInGraph", true
        ));

        int resultIndex = 0;
        for (DownstreamCallResult result : context.getResults()) {
            reporter.emit(SupervisorProgressSupport.STAGE_INVOKING, 60 + (resultIndex * 3),
                    "✓ " + result.agentKey() + " 실행 완료",
                    Map.of(
                            "agentKey", result.agentKey(),
                            "status", result.status(),
                            "errorCode", safe(result.errorCode()),
                            "payloadLength", result.payload() == null ? 0 : result.payload().length()
                    ));
            resultIndex++;
        }

        reporter.emit(SupervisorProgressSupport.STAGE_INVOKING, 75, "모든 하위 에이전트 실행 완료 (그래프 내에서 처리됨)", Map.of(
                "resultsCount", context.getResults().size()
        ));
    }

    /**
     * 라우팅/결과 유무에 따라 경고 로그 및 사용자 진행 메시지를 출력한다.
     *
     * @param request supervisor 요청
     * @param context planning context
     * @param reporter 진행 이벤트 reporter
     */
    public void emitRoutingWarnings(
            SupervisorAgentRequest request,
            SupervisorPlanningContext context,
            ProgressReporter reporter
    ) {
        if (context.getRoutingPlans().isEmpty()) {
            logger.warn("Supervisor planned no downstream calls sessionId={}, message={}",
                    request.sessionId(), request.message());
            reporter.emit(SupervisorProgressSupport.STAGE_ROUTING, 75, "라우팅 계획: 직접 응답(하위 에이전트 호출 없음)", Map.of());
            return;
        }
        if (context.getResults().isEmpty()) {
            logger.warn("Supervisor routing exists but downstream results are empty sessionId={}, planCount={}",
                    request.sessionId(), context.getRoutingPlans().size());
        }
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

    private String summarizeResult(DownstreamCallResult result) {
        if (result == null) {
            return "null-result";
        }
        return "agentKey=" + result.agentKey()
                + ", status=" + result.status()
                + ", errorCode=" + safe(result.errorCode())
                + ", payloadLength=" + (result.payload() == null ? 0 : result.payload().length());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
