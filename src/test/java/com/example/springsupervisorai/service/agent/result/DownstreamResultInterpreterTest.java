package com.example.springsupervisorai.service.agent.result;

import com.example.springsupervisorai.model.DownstreamCallResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamResultInterpreterTest {

    @Test
    void assessReturnsFailedWhenErrorCodeExists() {
        DownstreamCallResult result = new DownstreamCallResult(
                "reservation",
                "t1",
                "COMPLETED",
                "{\"status\":\"COMPLETED\"}",
                "DOWNSTREAM_TIMEOUT",
                "timeout"
        );

        var assessment = DownstreamResultInterpreter.assess(result);

        assertThat(assessment.outcome()).isEqualTo(DownstreamResultInterpreter.Outcome.FAILED);
    }

    @Test
    void assessReturnsFailedWhenPayloadContainsErrorToken() {
        DownstreamCallResult result = new DownstreamCallResult(
                "reservation",
                "t1",
                "COMPLETED",
                "[ERROR][REQUEST_FAILED] reservation failed",
                "",
                ""
        );

        var assessment = DownstreamResultInterpreter.assess(result);

        assertThat(assessment.outcome()).isEqualTo(DownstreamResultInterpreter.Outcome.FAILED);
        assertThat(assessment.reason()).contains("payload");
    }

    @Test
    void assessReturnsSuccessForCompletedWithoutFailureSignal() {
        DownstreamCallResult result = new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                "{\"status\":\"COMPLETED\",\"items\":[]}",
                "",
                "",
                false,
                "",
                "",
                "",
                Map.of()
        );

        var assessment = DownstreamResultInterpreter.assess(result);

        assertThat(assessment.outcome()).isEqualTo(DownstreamResultInterpreter.Outcome.SUCCESS);
    }

    @Test
    void assessReturnsFailedWhenPayloadContainsMissingRequiredParamsToken() {
        DownstreamCallResult result = new DownstreamCallResult(
                "sale-product",
                "t3",
                "COMPLETED",
                "[MISSING_REQUIRED_PARAMS] tool=createAutoCopySaleProducts, missing=[], message=필수 입력값 부족",
                "",
                ""
        );

        var assessment = DownstreamResultInterpreter.assess(result);

        assertThat(assessment.outcome()).isEqualTo(DownstreamResultInterpreter.Outcome.FAILED);
        assertThat(assessment.reason()).contains("missing_required_params");
    }

    @Test
    void assessReturnsFailedWhenPayloadContainsPolicySkippedToken() {
        DownstreamCallResult result = new DownstreamCallResult(
                "sale-product",
                "t4",
                "COMPLETED",
                "[POLICY_SKIPPED][MAX_CALLS_PER_REQUEST] server=sale-product, tool=createAutoCopySaleProducts, max=1",
                "",
                ""
        );

        var assessment = DownstreamResultInterpreter.assess(result);

        assertThat(assessment.outcome()).isEqualTo(DownstreamResultInterpreter.Outcome.FAILED);
        assertThat(assessment.reason()).contains("policy_skipped");
    }

    @Test
    void assessReturnsFailedWhenNestedResponseContainsToolFailureMarker() {
        DownstreamCallResult result = new DownstreamCallResult(
                "reservation",
                "t5",
                "COMPLETED",
                """
                {"id":"task-1","status":"COMPLETED","response":"도구 사용 결과: 실패\\n예약 생성 요청이 실패하였습니다."}
                """.trim(),
                "",
                ""
        );

        var assessment = DownstreamResultInterpreter.assess(result);

        assertThat(assessment.outcome()).isEqualTo(DownstreamResultInterpreter.Outcome.FAILED);
        assertThat(assessment.reason()).contains("tool_result_failed_marker");
    }
}
