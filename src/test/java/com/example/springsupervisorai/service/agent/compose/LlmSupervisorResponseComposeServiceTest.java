package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiComposePromptProviderRegistry;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
import com.example.springsupervisorai.service.agent.a2ui.product.BookingProductA2uiTemplate;
import com.example.springsupervisorai.service.agent.a2ui.product.DefaultSupervisorProductInfoA2uiService;
import com.example.springsupervisorai.service.agent.a2ui.product.PricingProductA2uiTemplate;
import com.example.springsupervisorai.service.agent.a2ui.product.ProductA2uiComposePromptProvider;
import com.example.springsupervisorai.service.agent.a2ui.product.ProductA2uiTemplateRegistry;
import com.example.springsupervisorai.service.agent.a2ui.product.SummaryProductA2uiTemplate;
import com.example.springsupervisorai.service.agent.a2ui.product.TimelineProductA2uiTemplate;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmSupervisorResponseComposeServiceTest {

    @Test
    void streamComposeBypassesLlmWhenOnlyFailuresExist() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                emptyA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "연차 신청", "openai");
        context.addResult(new DownstreamCallResult(
                "reservation",
                "t1",
                "COMPLETED",
                "[ERROR][REQUEST_FAILED] unsupported request",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();
        String merged = String.join("", chunks == null ? List.of() : chunks);

        assertThat(merged).contains("요청을 완료하지 못했습니다.");
        assertThat(merged).contains("FAILED");
        verifyNoInteractions(llmRuntime);
    }

    @Test
    void streamComposeUsesLlmWhenSuccessExistsAndIncludesNormalizedFieldsInPrompt() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.stream(anyString(), eq("openai"), eq("s1"))).thenReturn(Flux.just("정상 응답"));

        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                emptyA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "상품 추천", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                "{\"status\":\"COMPLETED\",\"items\":[]}",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();
        String merged = String.join("", chunks == null ? List.of() : chunks);

        assertThat(merged).isEqualTo("정상 응답");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRuntime).stream(promptCaptor.capture(), eq("openai"), eq("s1"));
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("normalizedOutcome=SUCCESS");
        assertThat(prompt).contains("successCount=1");
    }

    @Test
    void streamComposeKeepsMixedOutcomeAsMixedAndDoesNotBypassLlm() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.stream(anyString(), eq("openai"), eq("s1"))).thenReturn(Flux.just("부분 성공 응답"));

        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                emptyA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "복합 요청", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                "{\"status\":\"COMPLETED\"}",
                "",
                ""
        ));
        context.addResult(new DownstreamCallResult(
                "reservation",
                "t3",
                "COMPLETED",
                "[ERROR][REQUEST_FAILED] unsupported request",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();
        String merged = String.join("", chunks == null ? List.of() : chunks);

        assertThat(merged).isEqualTo("부분 성공 응답");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRuntime).stream(promptCaptor.capture(), eq("openai"), eq("s1"));
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("overallOutcome=MIXED");
        assertThat(prompt).contains("successCount=1");
        assertThat(prompt).contains("failedCount=1");
    }

    @Test
    void streamComposeReturnsA2uiEnvelopeWhenProductPayloadCanBeMapped() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.A2ui a2ui = new A2aSupervisorRoutingProperties.A2ui();
        a2ui.setEnabled(true);
        properties.setA2ui(a2ui);
        when(llmRuntime.complete(anyString(), eq("openai"), eq("s1")))
                .thenReturn("{\"message\":\"테스트 상품 상품 상세를 준비했습니다.\",\"selectedView\":\"summary\"}");
        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                properties,
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                productA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "상품 상세", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                """
                {"id":"t2","status":"COMPLETED","response":{"data":{"baseProductInfo":{"saleProdCd":"AAP331260523TG1","saleProdNm":"테스트 상품","depDay":"20260523","arrDay":"20260602","depCityNm":"인천","arrCityNm":"방콕","trvlNgtCnt":10,"trvlDayCnt":11,"adtTotlAmt":355000,"thmNm":"밍글링 투어","brndNm":"스탠다드","depFlgtCd":"TG0657","arrFlgtCd":"TG0658","rppdCntntInfoList":[{"rprsProdCntntUrlAdrs":"https://example.com/a.jpg"}]}}}}
                """.trim(),
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();

        assertThat(chunks).isNotNull();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains("테스트 상품");
        assertThat(chunks.get(1)).startsWith("[[A2UI]]");
        assertThat(chunks.get(1)).contains("\"surfaceUpdate\"");
        assertThat(chunks.get(1)).contains("\"beginRendering\"");
        assertThat(chunks.get(1)).contains("\"catalogId\":\"https://a2ui.org/specification/v0_8/standard_catalog_definition.json\"");
        assertThat(chunks.get(1)).contains("\"Card\"");
        assertThat(chunks.get(1)).contains("\"TextField\"");
        assertThat(chunks.get(1)).contains("\"Button\"");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRuntime).complete(promptCaptor.capture(), eq("openai"), eq("s1"));
        assertThat(promptCaptor.getValue()).contains("catalog=templates:");
        assertThat(promptCaptor.getValue()).contains("key: pricing");
    }

    @Test
    void streamComposeReturnsPricingDetailA2uiForPricingRequest() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.A2ui a2ui = new A2aSupervisorRoutingProperties.A2ui();
        a2ui.setEnabled(true);
        properties.setA2ui(a2ui);
        when(llmRuntime.complete(anyString(), eq("openai"), eq("s1")))
                .thenReturn("{\"message\":\"테스트 상품 요금 상세를 준비했습니다.\",\"selectedView\":\"pricing\"}");
        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                properties,
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                productA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "AAP331260523TG1 상품 요금 상세 보여줘", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                """
                {"structuredData":{"productDetail":{"baseProductInfo":{"saleProdCd":"AAP331260523TG1","saleProdNm":"테스트 상품","adtTotlAmt":355000,"chdTotlAmt":284000,"infTotlAmt":35500,"dnpyTlAmt":30000,"snglAddAmtDesc":"문의","trvlExpnInclList":[{"trvlExpnClstNm":"[교통]","trvlExpnDesc":"왕복항공권"}],"trvlChcExpnList":[{"trvlExpnClstNm":"[숙박]","trvlExpnDesc":"1인실 별도"}]}}}}
                """.trim(),
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.get(0)).contains("요금 상세");
        assertThat(chunks.get(1)).contains("\"surfaceUpdate\"");
        assertThat(chunks.get(1)).contains("\"beginRendering\"");
        assertThat(chunks.get(1)).doesNotContain("\"requestedView\"");
        assertThat(chunks.get(1)).contains("\"pricing_card\"");
        assertThat(chunks.get(1)).contains("\"TextField\"");
        verify(llmRuntime).complete(anyString(), eq("openai"), eq("s1"));
    }

    @Test
    void streamComposeReturnsTimelineA2uiForTimelineRequest() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.A2ui a2ui = new A2aSupervisorRoutingProperties.A2ui();
        a2ui.setEnabled(true);
        properties.setA2ui(a2ui);
        when(llmRuntime.complete(anyString(), eq("openai"), eq("s1")))
                .thenReturn("{\"message\":\"테스트 상품 일정 정보를 준비했습니다.\",\"selectedView\":\"timeline\"}");
        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                properties,
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                productA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "AAP331260523TG1 상품 일정 보기", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                """
                {"structuredData":{"productDetail":{"baseProductInfo":{"saleProdCd":"AAP331260523TG1","saleProdNm":"테스트 상품"},"itineraryInfo":{"meetInfoBcVo":{"sndgMeetDt":"20260523","sndgMeetTm":"1855","aptCd":"ICN"},"schdInfoList":[{"schdDay":1,"strtDt":"20260523","strDow":"토","infltNgtYn":"N","htlInfoList":[{"htlKoNm":"두짓 타니 방콕 호텔","locaDesc":"시내중심"}]}]}}}}
                """.trim(),
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.get(0)).contains("일정 정보");
        assertThat(chunks.get(1)).contains("\"surfaceUpdate\"");
        assertThat(chunks.get(1)).contains("\"beginRendering\"");
        assertThat(chunks.get(1)).doesNotContain("\"requestedView\"");
        assertThat(chunks.get(1)).contains("\"timeline_card\"");
        assertThat(chunks.get(1)).contains("\"Button\"");
        verify(llmRuntime).complete(anyString(), eq("openai"), eq("s1"));
    }

    @Test
    void streamComposeReturnsBookingA2uiForBookingRequest() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.A2ui a2ui = new A2aSupervisorRoutingProperties.A2ui();
        a2ui.setEnabled(true);
        properties.setA2ui(a2ui);
        when(llmRuntime.complete(anyString(), eq("openai"), eq("s1")))
                .thenReturn("{\"message\":\"테스트 상품 예약 정보를 준비했습니다.\",\"selectedView\":\"booking\"}");
        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                properties,
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                productA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "이 상품으로 예약 진행해줘", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                """
                {"structuredData":{"productDetail":{"baseProductInfo":{"saleProdCd":"AAP331260523TG1","saleProdNm":"테스트 상품","depDay":"20260523","arrDay":"20260602","adtTotlAmt":355000}}}}
                """.trim(),
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.get(0)).contains("예약 정보");
        assertThat(chunks.get(1)).contains("\"reservation_card\"");
        assertThat(chunks.get(1)).contains("\"reservation_submit\"");
        verify(llmRuntime).complete(anyString(), eq("openai"), eq("s1"));
    }

    @Test
    void streamComposeDoesNotUseA2uiWhenFeatureDisabled() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.stream(anyString(), eq("openai"), eq("s1"))).thenReturn(Flux.just("기존 compose"));

        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                productA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "상품 상세", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                """
                {"id":"t2","status":"COMPLETED","response":{"data":{"baseProductInfo":{"saleProdCd":"AAP331260523TG1","saleProdNm":"테스트 상품","depDay":"20260523","arrDay":"20260602","adtTotlAmt":355000}}}}
                """.trim(),
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();

        assertThat(chunks).containsExactly("기존 compose");
        verify(llmRuntime).stream(anyString(), eq("openai"), eq("s1"));
    }

    @Test
    void streamComposeSkipsA2uiComposePromptWhenNoDomainPromptProviderMatches() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.A2ui a2ui = new A2aSupervisorRoutingProperties.A2ui();
        a2ui.setEnabled(true);
        properties.setA2ui(a2ui);
        when(llmRuntime.stream(anyString(), eq("openai"), eq("s1"))).thenReturn(Flux.just("일반 compose"));

        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                properties,
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                productA2uiService(),
                a2uiComposePromptProviderRegistry(),
                new ObjectMapper()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "예약 현황 알려줘", "openai");
        context.addResult(new DownstreamCallResult(
                "reservation",
                "t2",
                "COMPLETED",
                "{\"status\":\"COMPLETED\",\"reservationId\":\"R-1\"}",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();

        assertThat(chunks).containsExactly("일반 compose");
        verify(llmRuntime).stream(anyString(), eq("openai"), eq("s1"));
    }

    private static SupervisorA2uiService emptyA2uiService() {
        return (context, selectedView, message) -> java.util.Optional.empty();
    }

    private static SupervisorA2uiService productA2uiService() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProductA2uiTemplateRegistry registry = new ProductA2uiTemplateRegistry(List.of(
                new SummaryProductA2uiTemplate(),
                new PricingProductA2uiTemplate(),
                new TimelineProductA2uiTemplate(),
                new BookingProductA2uiTemplate()
        ));
        return new DefaultSupervisorProductInfoA2uiService(objectMapper, registry);
    }

    private static A2uiComposePromptProviderRegistry a2uiComposePromptProviderRegistry() {
        return new A2uiComposePromptProviderRegistry(List.of(new ProductA2uiComposePromptProvider()));
    }

    private static SupervisorPromptProperties composePromptProperties() {
        SupervisorPromptProperties promptProperties = new SupervisorPromptProperties();
        promptProperties.setComposeSystem("compose-system");
        promptProperties.setComposeTemplate("""
                {composeSystem}
                user={userMessage}
                history={history}
                results={downstreamResults}
                summary={downstreamOutcomeSummary}
                """);
        promptProperties.setComposeA2uiSystem("compose-a2ui-system");
        promptProperties.setComposeA2uiTemplate("""
                {composeA2uiSystem}
                keys={a2uiTemplateKeys}
                catalog={a2uiTemplateCatalog}
                user={userMessage}
                history={history}
                results={downstreamResults}
                summary={downstreamOutcomeSummary}
                """);
        promptProperties.setComposeA2uiRepairTemplate("""
                keys={a2uiTemplateKeys}
                catalog={a2uiTemplateCatalog}
                invalid={invalidOutput}
                """);
        return promptProperties;
    }
}
