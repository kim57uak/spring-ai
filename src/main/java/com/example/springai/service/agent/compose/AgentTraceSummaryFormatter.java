package com.example.springai.service.agent.compose;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 계획/실행 이력을 사람이 읽기 쉬운 요약 문자열로 변환하는 포매터.
 */
@Component
public class AgentTraceSummaryFormatter {

    public AgentTraceSummaryFormatter() {
    }

    /**
     * 사용자에게 노출할 판단 요약/선택 도구 섹션을 구성한다.
     * <p>
     * 반환 형식은 고정 헤더를 가진 멀티라인 텍스트다.
     */
    public String format(PlanningContext context) {
        String thinkingSummary = buildThinkingSummary(context);
        String selectedTools = buildSelectedTools(context);

        return """
                [판단 상세]
                %s

                [선택된 도구]
                %s

                """.formatted(thinkingSummary, selectedTools);
    }

    /**
     * 사고 요약 섹션을 생성한다.
     * <p>
     * 실행 이력이 있으면 실제 실행 근거를 우선 사용하고,
     * 없으면 planner 결과를 기반으로 설명을 구성한다.
     */
    private String buildThinkingSummary(PlanningContext context) {
        List<String> executedTrace = context.getToolTrace();
        if (executedTrace != null && !executedTrace.isEmpty()) {
            List<ExecutedStep> steps = parseExecutedSteps(executedTrace);
            List<String> reasoning = new ArrayList<>();
            for (ExecutedStep step : steps) {
                if (step.reason().isBlank()) {
                    reasoning.add("- " + step.call() + " 호출이 필요하다고 판단했습니다.");
                } else {
                    reasoning.add("- " + step.reason());
                }
            }
            return reasoning.stream()
                    .collect(Collectors.joining("\n"));
        }

        List<ToolPlan> plans = context.getPlans();
        if (plans == null || plans.isEmpty()) {
            return "- 플래너 결과가 비어 있어 도구 없이 답변을 생성합니다.";
        }
        boolean hasTool = plans.stream().anyMatch(ToolPlan::toolRequired);
        if (!hasTool) {
            String reason = plans.get(0).reason();
            return "- 도구 호출 없이 LLM만으로 응답 가능하다고 판단했습니다. reason=" + safe(reason);
        }

        return plans.stream()
                .filter(ToolPlan::toolRequired)
                .map(plan -> "- " + safe(plan.serverName()) + "/" + safe(plan.toolName()) + " 선택: " + safe(plan.reason()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 선택된 도구 섹션을 생성한다.
     * <p>
     * 실행 이력이 있으면 중복을 제거한 실제 호출 내역을 사용한다.
     */
    private String buildSelectedTools(PlanningContext context) {
        List<String> executedTrace = context.getToolTrace();
        if (executedTrace != null && !executedTrace.isEmpty()) {
            List<ExecutedStep> steps = parseExecutedSteps(executedTrace);
            Set<String> uniqueCalls = new LinkedHashSet<>();
            for (ExecutedStep step : steps) {
                if (step.args().isBlank()) {
                    uniqueCalls.add("- " + step.call());
                } else {
                    uniqueCalls.add("- " + step.call() + " args=" + step.args());
                }
            }
            return String.join("\n", uniqueCalls);
        }

        List<ToolPlan> plans = context.getPlans();
        if (plans == null || plans.isEmpty()) {
            return "- 없음";
        }
        List<ToolPlan> requiredPlans = plans.stream().filter(ToolPlan::toolRequired).toList();
        if (requiredPlans.isEmpty()) {
            return "- 없음 (플래너가 COMPLETE 반환)";
        }
        return requiredPlans.stream()
                .map(plan -> "- " + safe(plan.serverName()) + "/" + safe(plan.toolName()) + " args=" + plan.arguments())
                .collect(Collectors.joining("\n"));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }

    /**
     * 실행 trace 문자열에서 호출/인자/사유를 구조화 형태로 분해한다.
     */
    private List<ExecutedStep> parseExecutedSteps(List<String> executedTrace) {
        List<ExecutedStep> steps = new ArrayList<>();
        for (String raw : executedTrace) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String line = raw.trim();
            int argsIdx = line.indexOf(" args=");
            int reasonIdx = line.indexOf(" reason=");

            String call = line;
            String args = "";
            String reason = "";

            if (argsIdx >= 0) {
                call = line.substring(0, argsIdx).trim();
                if (reasonIdx > argsIdx) {
                    args = line.substring(argsIdx + " args=".length(), reasonIdx).trim();
                } else {
                    args = line.substring(argsIdx + " args=".length()).trim();
                }
            }
            if (reasonIdx >= 0) {
                reason = line.substring(reasonIdx + " reason=".length()).trim();
            }
            steps.add(new ExecutedStep(safe(call), args, reason));
        }
        return steps;
    }

    private record ExecutedStep(String call, String args, String reason) {
    }
}
