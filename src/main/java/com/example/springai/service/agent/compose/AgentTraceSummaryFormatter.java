package com.example.springai.service.agent.compose;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentTraceSummaryFormatter {

    public AgentTraceSummaryFormatter() {
    }

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
