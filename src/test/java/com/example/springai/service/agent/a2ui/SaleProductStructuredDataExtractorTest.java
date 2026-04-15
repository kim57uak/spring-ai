package com.example.springai.service.agent.a2ui;

import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SaleProductStructuredDataExtractorTest {

    private final SaleProductStructuredDataExtractor extractor =
            new SaleProductStructuredDataExtractor(new ObjectMapper());

    @Test
    void extractsStructuredDataForSaleProductPayload() {
        PlanningContext context = contextWithPayload("""
                {
                  "baseProductInfo": {
                    "saleProdCd": "AAP331260523TG1",
                    "saleProdNm": "테스트 상품"
                  }
                }
                """);

        Map<String, Object> result = extractor.extract(context, AgentScopeName.PRODUCT);

        assertThat(result)
                .containsEntry("type", "product_detail")
                .containsKey("productDetail");
    }

    @Test
    void returnsEmptyForNonProductScope() {
        PlanningContext context = contextWithPayload("""
                {
                  "baseProductInfo": {
                    "saleProdCd": "AAP331260523TG1"
                  }
                }
                """);

        assertThat(extractor.supports(context, AgentScopeName.SEARCH)).isFalse();
        assertThat(extractor.extract(context, AgentScopeName.SEARCH)).isEmpty();
    }

    @Test
    void returnsEmptyWhenPayloadDoesNotContainSaleProductDetail() {
        PlanningContext context = contextWithPayload("""
                {
                  "result": "ok"
                }
                """);

        assertThat(extractor.extract(context, AgentScopeName.PRODUCT)).isEmpty();
    }

    @Test
    void extractsStructuredDataFromWrappedToolPayload() {
        PlanningContext context = contextWithPayload("""
                [action-execution::sale-product/getSaleProductDetails]
                {"data":{"baseProductInfo":{"saleProdCd":"AAP331260523TG1","saleProdNm":"테스트 상품"}}}

                """);

        Map<String, Object> result = extractor.extract(context, AgentScopeName.PRODUCT);

        assertThat(result)
                .containsEntry("type", "product_detail")
                .containsKey("productDetail");
    }

    private PlanningContext contextWithPayload(String payload) {
        PlanningContext context = new PlanningContext(
                "session-1",
                "상품 상세 조회",
                "openai"
        );
        context.replaceHistory(List.of());
        context.setExecutionResult(new ToolExecutionResult(
                "sale-product",
                "getSaleProductDetails",
                payload,
                Map.of(),
                true,
                true,
                false
        ));
        return context;
    }
}
