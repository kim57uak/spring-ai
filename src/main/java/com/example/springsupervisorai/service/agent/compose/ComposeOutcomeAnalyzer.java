package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * downstream 결과를 compose 관점의 outcome 요약으로 변환한다.
 */
@Component
public class ComposeOutcomeAnalyzer {

    /**
     * compose 입력 컨텍스트를 요약한다.
     *
     * @param context compose 입력 컨텍스트
     * @return 결과 outcome summary
     */
    public ComposeOutcomeSummary summarize(SupervisorPlanningContext context) {
        if (context == null || context.getResults() == null || context.getResults().isEmpty()) {
            return new ComposeOutcomeSummary(0, 0, 0, List.of());
        }
        int successCount = 0;
        int failedCount = 0;
        int unknownCount = 0;
        List<ResultOutcome> resultOutcomes = new ArrayList<>();
        for (DownstreamCallResult result : context.getResults()) {
            DownstreamResultInterpreter.Assessment assessment = DownstreamResultInterpreter.assess(result);
            if (assessment.outcome() == DownstreamResultInterpreter.Outcome.SUCCESS) {
                successCount++;
            } else if (assessment.outcome() == DownstreamResultInterpreter.Outcome.FAILED) {
                failedCount++;
            } else {
                unknownCount++;
            }
            resultOutcomes.add(new ResultOutcome(result, assessment));
        }
        return new ComposeOutcomeSummary(successCount, failedCount, unknownCount, List.copyOf(resultOutcomes));
    }

    /**
     * 단일 downstream 결과와 그 해석 결과를 묶는 typed outcome.
     *
     * @param result downstream 결과
     * @param assessment 해석 결과
     */
    public record ResultOutcome(
            DownstreamCallResult result,
            DownstreamResultInterpreter.Assessment assessment
    ) {
    }

    /**
     * compose에 필요한 outcome 카운트와 세부 결과를 담는 요약 모델.
     *
     * @param successCount 성공 건수
     * @param failedCount 실패 건수
     * @param unknownCount 미분류 건수
     * @param resultOutcomes 상세 결과 목록
     */
    public record ComposeOutcomeSummary(
            int successCount,
            int failedCount,
            int unknownCount,
            List<ResultOutcome> resultOutcomes
    ) {
        public boolean hasAnyFailure() {
            return failedCount > 0;
        }

        public boolean hasFailureWithoutSuccess() {
            return failedCount > 0 && successCount == 0;
        }

        public String overallOutcome() {
            if (failedCount > 0 && successCount == 0) {
                return "ALL_FAILED";
            }
            if (successCount > 0 && failedCount == 0) {
                return "ALL_SUCCESS";
            }
            if (successCount > 0 && failedCount > 0) {
                return "MIXED";
            }
            return "UNKNOWN";
        }
    }
}
