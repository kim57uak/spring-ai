package com.example.springai.service.agent.graph;

import com.example.springai.model.agent.AgentGraphState;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;
import com.example.springai.service.agent.execute.ToolExecutionService;
import com.example.springai.service.agent.plan.PlanningService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * LangGraph 기반 에이전트 상태 그래프 팩토리.
 * PLAN -> (EXECUTE?) -> COMPOSE 구조로 계획/도구실행/응답생성을 분리한다.
 */
@Component
public class LangGraphAgentStateGraphFactory implements AgentStateGraphFactory {

    private static final Logger logger = LoggerFactory.getLogger(LangGraphAgentStateGraphFactory.class);
    private static final String START = "__START__";
    private static final String END = "__END__";

    private static final String PLAN_NODE = "plan";
    private static final String EXECUTE_NODE = "execute";
    private static final String COMPOSE_NODE = "compose";
    private static final int MAX_TOOL_ITERATIONS = 4;

    private final PlanningService planningService;
    private final ToolExecutionService toolExecutionService;
    private final CompiledGraph<AgentGraphState> compiledGraph;

    public LangGraphAgentStateGraphFactory(
            PlanningService planningService,
            ToolExecutionService toolExecutionService
    ) throws GraphStateException {
        this.planningService = planningService;
        this.toolExecutionService = toolExecutionService;
        this.compiledGraph = buildGraph().compile();
    }

    @Override
    public CompiledGraph<AgentGraphState> getCompiledGraph() {
        return compiledGraph;
    }

    private StateGraph<AgentGraphState> buildGraph() throws GraphStateException {
        StateGraph<AgentGraphState> graph = new StateGraph<>(AgentGraphState::new);

        graph.addNode(PLAN_NODE, planNode());
        graph.addNode(EXECUTE_NODE, executeNode());
        graph.addNode(COMPOSE_NODE, composeNode());

        graph.addEdge(START, PLAN_NODE);
        graph.addConditionalEdges(PLAN_NODE, routeAfterPlan(), Map.of(
                "execute", EXECUTE_NODE,
                "compose", COMPOSE_NODE
        ));
        graph.addEdge(EXECUTE_NODE, COMPOSE_NODE);
        graph.addEdge(COMPOSE_NODE, END);

        return graph;
    }

    /**
     * PLAN 노드:
     * 사용자 요청과 컨텍스트를 바탕으로 도구 실행 계획 목록을 생성한다.
     */
    private AsyncNodeAction<AgentGraphState> planNode() {
        return state -> {
            PlanningContext context = state.toPlanningContext();
            List<ToolPlan> plans = planningService.plan(context);
            ToolPlan primaryPlan = plans.get(0);
            Map<String, Object> updates = new HashMap<>();
            updates.put(AgentGraphState.CURRENT_NODE, "PLANNED");
            updates.put(AgentGraphState.PLANS, plans.stream().map(this::toPlanMap).toList());
            updates.put(AgentGraphState.PLAN_CAPABILITY, primaryPlan.capability());
            updates.put(AgentGraphState.PLAN_SERVER, primaryPlan.serverName());
            updates.put(AgentGraphState.PLAN_TOOL, primaryPlan.toolName());
            updates.put(AgentGraphState.PLAN_REASON, primaryPlan.reason());
            updates.put(AgentGraphState.PLAN_REQUIRED, plans.stream().anyMatch(ToolPlan::toolRequired));
            return CompletableFuture.completedFuture(updates);
        };
    }

    /**
     * EXECUTE 노드:
     * 계획된 도구를 최대 MAX_TOOL_ITERATIONS 내에서 실행하고 결과를 상태에 누적한다.
     * 중복 실행 방지를 위해 서버/도구/인자 시그니처를 dedup 키로 사용한다.
     */
    private AsyncNodeAction<AgentGraphState> executeNode() {
        return state -> {
            PlanningContext context = state.toPlanningContext();
            Queue<ToolPlan> queue = new ArrayDeque<>(context.getPlans());
            Set<String> executedSignatures = new HashSet<>();

            StringBuilder payload = new StringBuilder();
            ToolExecutionResult firstExecuted = null;
            boolean allSuccess = true;
            boolean anyExecuted = false;
            boolean terminalAfterExecution = false;
            int iterations = 0;
            List<String> executedTrace = new java.util.ArrayList<>();

            while (!queue.isEmpty() && iterations < MAX_TOOL_ITERATIONS) {
                ToolPlan plan = queue.poll();
                if (plan == null || !plan.toolRequired()) {
                    continue;
                }
                String signature = signature(plan);
                if (executedSignatures.contains(signature)) {
                    continue;
                }
                executedSignatures.add(signature);

                ToolExecutionResult result = toolExecutionService.execute(plan, context);
                logExecutionResult(context, plan, result);
                if (firstExecuted == null && result.executed()) {
                    firstExecuted = result;
                }
                anyExecuted = anyExecuted || result.executed();
                allSuccess = allSuccess && result.success();
                terminalAfterExecution = terminalAfterExecution || result.terminalAfterExecution();
                context.setExecutionResult(result);
                String executedLine = result.serverName() + "/" + result.toolName()
                        + " args=" + result.usedArguments()
                        + " reason=" + plan.reason();
                context.addToolTrace(executedLine);
                executedTrace.add(executedLine);
                iterations++;

                if (result.terminalAfterExecution()) {
                    // mutation 정책 도구는 1회 처리(성공/실패/정책차단 포함) 후 즉시 종료한다.
                    // 큐에 중복 mutation plan이 남아 있어도 추가 시도를 하지 않는다.
                    queue.clear();
                }

                payload.append("[")
                        .append(plan.capability())
                        .append("::")
                        .append(result.serverName())
                        .append("/")
                        .append(result.toolName())
                        .append("]\n")
                        .append(result.rawPayload())
                        .append("\n\n");

                if (!terminalAfterExecution && iterations < MAX_TOOL_ITERATIONS) {
                    List<ToolPlan> nextPlans = planningService.plan(context);
                    for (ToolPlan next : nextPlans) {
                        if (!next.toolRequired()) {
                            continue;
                        }
                        String nextSignature = signature(next);
                        if (!executedSignatures.contains(nextSignature)) {
                            queue.offer(next);
                        }
                    }
                }
            }

            if (!anyExecuted) {
                payload.append("NO_TOOL_EXECUTED");
                logger.warn("Agent tool execution skipped all plans. sessionId={}, requestedPlans={}, iterations={}",
                        context.getSessionId(), context.getPlans().size(), iterations);
            }
            if (!allSuccess) {
                logger.warn("Agent tool execution completed with failures. sessionId={}, iterations={}, traceCount={}",
                        context.getSessionId(), iterations, executedTrace.size());
            }

            Map<String, Object> updates = new HashMap<>();
            updates.put(AgentGraphState.CURRENT_NODE, "EXECUTING");
            updates.put(AgentGraphState.EXEC_SERVER, firstExecuted != null ? firstExecuted.serverName() : "");
            updates.put(AgentGraphState.EXEC_TOOL, firstExecuted != null ? firstExecuted.toolName() : "");
            updates.put(AgentGraphState.EXEC_PAYLOAD, payload.toString());
            updates.put(AgentGraphState.EXEC_ARGS, firstExecuted != null ? firstExecuted.usedArguments() : Map.of());
            updates.put(AgentGraphState.EXEC_TRACE, executedTrace);
            updates.put(AgentGraphState.EXEC_SUCCESS, allSuccess);
            updates.put(AgentGraphState.EXEC_EXECUTED, anyExecuted);
            updates.put(AgentGraphState.EXEC_TERMINAL, terminalAfterExecution);
            return CompletableFuture.completedFuture(updates);
        };
    }

    private String signature(ToolPlan plan) {
        // 동일 도구 중복 실행 방지를 위한 식별자.
        return plan.serverName() + "|" + plan.toolName() + "|" + plan.arguments();
    }

    /**
     * 하위 MCP 실행 결과를 운영 로그로 남긴다.
     * 실패/스킵을 WARN으로 기록해 장애 분석 시점을 명확히 한다.
     */
    private void logExecutionResult(PlanningContext context, ToolPlan plan, ToolExecutionResult result) {
        String sessionId = context == null ? "" : context.getSessionId();
        if (result == null) {
            logger.warn("Agent tool execution returned null result. sessionId={}, server={}, tool={}",
                    sessionId, plan.serverName(), plan.toolName());
            return;
        }
        if (!result.executed()) {
            logger.warn("Agent tool execution skipped. sessionId={}, server={}, tool={}, reason={}, payloadPreview={}",
                    sessionId, plan.serverName(), plan.toolName(), plan.reason(), preview(result.rawPayload()));
            return;
        }
        if (!result.success()) {
            logger.warn("Agent tool execution failed. sessionId={}, server={}, tool={}, reason={}, argsKeys={}, payloadPreview={}",
                    sessionId,
                    result.serverName(),
                    result.toolName(),
                    plan.reason(),
                    result.usedArguments() == null ? List.of() : result.usedArguments().keySet(),
                    preview(result.rawPayload()));
            return;
        }
        logger.info("Agent tool execution success. sessionId={}, server={}, tool={}, payloadLength={}",
                sessionId, result.serverName(), result.toolName(), result.rawPayload() == null ? 0 : result.rawPayload().length());
    }

    private String preview(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return "";
        }
        String oneLine = rawPayload.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 220 ? oneLine : oneLine.substring(0, 220) + "...";
    }

    private AsyncNodeAction<AgentGraphState> composeNode() {
        // 실제 응답 생성은 오케스트레이터의 compose 서비스가 수행하므로 상태 마킹만 수행한다.
        return state -> CompletableFuture.completedFuture(Map.of(
                AgentGraphState.CURRENT_NODE, "COMPOSING"
        ));
    }

    /**
     * PLAN 결과에서 도구 필요 여부를 읽어 EXECUTE 또는 COMPOSE로 분기한다.
     */
    private AsyncEdgeAction<AgentGraphState> routeAfterPlan() {
        return state -> CompletableFuture.completedFuture(
                state.value(AgentGraphState.PLAN_REQUIRED).map(Boolean.class::cast).orElse(false)
                        ? "execute" : "compose"
        );
    }

    private Map<String, Object> toPlanMap(ToolPlan plan) {
        return Map.of(
                "capability", plan.capability(),
                "serverName", plan.serverName(),
                "toolName", plan.toolName(),
                "reason", plan.reason(),
                "arguments", plan.arguments(),
                "toolRequired", plan.toolRequired()
        );
    }
}
