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
}
