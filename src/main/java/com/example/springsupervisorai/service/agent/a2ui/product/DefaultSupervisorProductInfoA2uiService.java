package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiDomainService;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * downstream 제품 결과로부터 렌더링된 A2UI 페이로드를 빌드하는 기본 제품 도메인 A2UI 서비스.
 * <p>
 * 성공적인 downstream 결과에서 제품 JSON을 추출하고 페이로드 추출기, 데이터 매퍼,
 * 메시지 빌더 파이프라인에 위임하여 모든 제품 소유 템플릿 뷰(요약, 가격, 일정, 예약, 생성 폼)를 처리한다.
 * 제품 결과는 없지만 라우팅 계획이 있는 경우 독립 실행형 생성 폼 렌더링을 지원한다.
 */
@Component
public class DefaultSupervisorProductInfoA2uiService implements SupervisorA2uiDomainService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSupervisorProductInfoA2uiService.class);
    private final ObjectMapper objectMapper;
    private final ProductA2uiTemplateRegistry templateRegistry;
    private final ProductPayloadExtractor payloadExtractor;
    private final ProductA2uiDataMapper dataMapper;
    private final ProductA2uiMessageBuilder messageBuilder;

    /**
     * Keeps product-specific A2UI flow control in one facade while delegating parsing, mapping and assembly.
     */
    public DefaultSupervisorProductInfoA2uiService(
            ObjectMapper objectMapper,
            ProductA2uiTemplateRegistry templateRegistry,
            ProductPayloadExtractor payloadExtractor,
            ProductA2uiDataMapper dataMapper,
            ProductA2uiMessageBuilder messageBuilder
    ) {
        this.objectMapper = objectMapper;
        this.templateRegistry = templateRegistry;
        this.payloadExtractor = payloadExtractor;
        this.dataMapper = dataMapper;
        this.messageBuilder = messageBuilder;
    }

    @Override
    public boolean supports(SupervisorPlanningContext context, A2uiTemplateView selectedView) {
        if (selectedView == null) {
            return false;
        }
        // 제품 도메인이 소유한 뷰만 처리
        boolean productOwnedView = selectedView == A2uiTemplateView.PACKAGE_SUMMARY
                || selectedView == A2uiTemplateView.PACKAGE_PRICING
                || selectedView == A2uiTemplateView.PACKAGE_TIMELINE
                || selectedView == A2uiTemplateView.PACKAGE_BOOKING
                || selectedView == A2uiTemplateView.PACKAGE_SALE_PRODUCT_CREATE_FORM;
        if (!productOwnedView) {
            return false;
        }
        // 생성 폼은 라우팅 계획만으로 충족 가능 (아직 결과 없음)
        if (selectedView == A2uiTemplateView.PACKAGE_SALE_PRODUCT_CREATE_FORM) {
            return hasSuccessfulProductResult(context) || hasProductRoutingPlan(context);
        }
        // 다른 뷰는 최소 하나의 성공적인 제품 결과 필요
        return hasSuccessfulProductResult(context);
    }

    /**
     * Builds a product A2UI render result by extracting product data from downstream results
     * and assembling protocol messages through the data mapper and message builder pipeline.
     *
     * @param context the current planning context containing downstream results
     * @param selectedView the template view selected by compose
     * @param message optional user message override; uses template default if blank
     * @return render result with display message and protocol JSON, or empty if no eligible product result
     */
    @Override
    public Optional<com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService.A2uiRenderResult> build(
            SupervisorPlanningContext context,
            A2uiTemplateView selectedView,
            String message
    ) {
        ProductA2uiTemplate template = templateRegistry.resolve(selectedView == null ? A2uiTemplateView.PACKAGE_SUMMARY : selectedView);
        // 독립형 생성 폼 처리 (제품 데이터 불필요)
        if (template.view() == A2uiTemplateView.PACKAGE_SALE_PRODUCT_CREATE_FORM) {
            Optional<com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService.A2uiRenderResult> standalone =
                    buildStandaloneCreationForm(context, message, template);
            if (standalone.isPresent()) {
                return standalone;
            }
        }
        if (context == null || context.getResults() == null || context.getResults().isEmpty()) {
            logger.info("Supervisor product A2UI skipped: no downstream results");
            return Optional.empty();
        }
        // A2UI 데이터를 제공할 수 있는 첫 번째 downstream 결과를 찾기 위해 순회
        for (DownstreamCallResult result : context.getResults()) {
            if (!isSuccessfulProductResult(result)) {
                continue;
            }
            logger.info("Supervisor product A2UI inspecting result sessionId={}, taskId={}, agentKey={}, payloadLength={}",
                    context.getSessionId(), result.taskId(), result.agentKey(),
                    result.payload() == null ? 0 : result.payload().length());
            // downstream 페이로드에서 제품 JSON 노드 추출
            Optional<JsonNode> productNode = payloadExtractor.extractProductNode(result.payload());
            if (productNode.isEmpty()) {
                logger.info("Supervisor product A2UI productDetail not found sessionId={}, taskId={}",
                        context.getSessionId(), result.taskId());
                continue;
            }
            // 추출된 제품 데이터로 프로토콜 메시지 빌드
            Optional<List<Map<String, Object>>> protocolMessages = buildProtocolMessages(productNode.get(), context, result, template);
            if (protocolMessages.isEmpty()) {
                logger.info("Supervisor product A2UI message build returned empty sessionId={}, taskId={}",
                        context.getSessionId(), result.taskId());
                continue;
            }
            try {
                String name = resolveDisplayName(productNode.get());
                String resolvedMessage = message == null || message.isBlank()
                        ? template.defaultMessage(name.isBlank() ? "상품" : name)
                        : message;
                logger.info("Supervisor product A2UI message built sessionId={}, taskId={}, requestedView={}",
                        context.getSessionId(), result.taskId(), template.view());
                return Optional.of(new com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService.A2uiRenderResult(
                        resolvedMessage,
                        objectMapper.writeValueAsString(protocolMessages.get())
                ));
            } catch (Exception ex) {
                logger.warn("Supervisor product A2UI serialization failed sessionId={}, taskId={}, error={}",
                        context.getSessionId(), result.taskId(), ex.getMessage());
                return Optional.empty();
            }
        }
        logger.info("Supervisor product A2UI skipped: no eligible product result sessionId={}", context.getSessionId());
        return Optional.empty();
    }

    private boolean isSuccessfulProductResult(DownstreamCallResult result) {
        if (result == null || !"product".equalsIgnoreCase(result.agentKey())) {
            return false;
        }
        return DownstreamResultInterpreter.assess(result).outcome() == DownstreamResultInterpreter.Outcome.SUCCESS;
    }

    private boolean hasSuccessfulProductResult(SupervisorPlanningContext context) {
        return context != null
                && context.getResults() != null
                && context.getResults().stream().anyMatch(this::isSuccessfulProductResult);
    }

    /**
     * Builds protocol messages by mapping the product JSON to a presentation model
     * and delegating to the message builder.
     */
    private Optional<List<Map<String, Object>>> buildProtocolMessages(
            JsonNode productRoot,
            SupervisorPlanningContext context,
            DownstreamCallResult result,
            ProductA2uiTemplate template
    ) {
        Optional<ProductPresentationModel> model = dataMapper.map(productRoot, template);
        if (model.isEmpty()) {
            logger.info("Supervisor product A2UI missing required fields sessionId={}, taskId={}, fields={}",
                    context.getSessionId(), result.taskId(), "template-required");
            return Optional.empty();
        }
        String surfaceId = "package-product-" + result.taskId();
        return Optional.of(messageBuilder.build(surfaceId, model.get(), template));
    }

    /**
     * Builds a standalone creation form render result from routing plan arguments
     * without requiring a downstream product result.
     */
    private Optional<com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService.A2uiRenderResult> buildStandaloneCreationForm(
            SupervisorPlanningContext context,
            String message,
            ProductA2uiTemplate template
    ) {
        if (!hasProductRoutingPlan(context)) {
            return Optional.empty();
        }
        try {
            ProductPresentationModel model = dataMapper.standaloneCreationSeed(
                    dataMapper.standaloneCreationArguments(context == null ? null : context.getRoutingPlans())
            );
            String resolvedMessage = message == null || message.isBlank()
                    ? "요청에 맞는 입력 화면을 준비했습니다."
                    : message;
            String surfaceId = "package-product-create-" + (context == null ? "standalone" : context.getSessionId());
            return Optional.of(new com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService.A2uiRenderResult(
                    resolvedMessage,
                    objectMapper.writeValueAsString(messageBuilder.build(surfaceId, model, template))
            ));
        } catch (Exception ex) {
            logger.warn("Supervisor standalone creation A2UI serialization failed sessionId={}, error={}",
                    context == null ? "" : context.getSessionId(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves the display name from the product JSON node using saleProdNm, saleProductCode, or a fallback.
     */
    private String resolveDisplayName(JsonNode productNode) {
        String detailName = productNode.path("baseProductInfo").path("saleProdNm").asText("").trim();
        if (!detailName.isBlank()) {
            return detailName;
        }
        String creationCode = productNode.path("saleProductCode").asText("").trim();
        if (!creationCode.isBlank()) {
            return creationCode;
        }
        return "상품";
    }

    private boolean hasProductRoutingPlan(SupervisorPlanningContext context) {
        return context != null
                && context.getRoutingPlans() != null
                && context.getRoutingPlans().stream().anyMatch(plan -> "product".equalsIgnoreCase(plan.agentKey()));
    }

}
