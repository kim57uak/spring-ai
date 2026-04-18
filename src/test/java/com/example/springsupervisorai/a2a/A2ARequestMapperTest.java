package com.example.springsupervisorai.a2a;

import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2ARequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final A2ARequestMapper mapper = new A2ARequestMapper(objectMapper);

    @Test
    void sendMessageShouldPreserveOriginalUserMessageWhenPlannerAddsShortHint() {
        String original = "예약을 생성해주세요. 판매상품코드: AAP331260523TG1, 예약자: 김병두, 인원 수: 1명, 연락처: 01038569626, 생년월일: 19740308";
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", original, "openai");
        RoutingPlan plan = new RoutingPlan(
                "reservation",
                "SendMessage",
                "reservation routing",
                1,
                Map.of("message", "예약 생성 요청 처리")
        );

        JsonNode params = mapper.toJsonRpcRequest(plan, context, "SendMessage").params();
        String sent = params.path("message").path("parts").path(0).path("text").asText("");

        assertThat(sent).isEqualTo(original);
    }

    @Test
    void messageSendShouldUseOriginalMessageWhenPlannerMessageIsSubset() {
        String original = "상품생성해줘\n상품코드: AAP331260523TG1\n출발시작일: 20260501\n출발종료일: 20260531\n전체대상: Y\n출발요일: MON,TUE";
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", original, "openai");
        RoutingPlan plan = new RoutingPlan(
                "product",
                "message/send",
                "product routing",
                1,
                Map.of("message", "상품생성해줘")
        );

        JsonNode params = mapper.toJsonRpcRequest(plan, context, "message/send").params();
        String sent = params.path("messageText").asText("");

        assertThat(sent).isEqualTo(original);
    }

    @Test
    void searchRequestMayUsePlannerExtractedPromptForFocusedDownstreamMessage() {
        String original = "최신 여행 트렌드 알려줘";
        String extracted = "여행 트렌드 2026년 4월 한국 outbound";
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", original, "openai");
        RoutingPlan plan = new RoutingPlan(
                "search",
                "message/send",
                "search routing",
                1,
                Map.of("message", extracted)
        );

        JsonNode params = mapper.toJsonRpcRequest(plan, context, "message/send").params();
        String sent = params.path("messageText").asText("");

        assertThat(sent).isEqualTo(extracted);
    }
}
