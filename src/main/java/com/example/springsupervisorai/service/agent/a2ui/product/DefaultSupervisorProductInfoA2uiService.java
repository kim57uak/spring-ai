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
        boolean productOwnedView = selectedView == A2uiTemplateView.SUMMARY
                || selectedView == A2uiTemplateView.PRICING
                || selectedView == A2uiTemplateView.TIMELINE
                || selectedView == A2uiTemplateView.BOOKING
                || selectedView == A2uiTemplateView.CREATION_FORM;
        if (!productOwnedView) {
            return false;
        }
        if (selectedView == A2uiTemplateView.CREATION_FORM) {
            return hasSuccessfulProductResult(context) || hasProductRoutingPlan(context);
        }
        return hasSuccessfulProductResult(context);
    }

    @Override
    public Optional<com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService.A2uiRenderResult> build(
            SupervisorPlanningContext context,
            A2uiTemplateView selectedView,
            String message
    ) {
        ProductA2uiTemplate template = templateRegistry.resolve(selectedView == null ? A2uiTemplateView.SUMMARY : selectedView);
        if (template.view() == A2uiTemplateView.CREATION_FORM) {
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
        for (DownstreamCallResult result : context.getResults()) {
            if (!isSuccessfulProductResult(result)) {
                continue;
            }
            logger.info("Supervisor product A2UI inspecting result sessionId={}, taskId={}, agentKey={}, payloadLength={}",
                    context.getSessionId(), result.taskId(), result.agentKey(),
                    result.payload() == null ? 0 : result.payload().length());
            Optional<JsonNode> productNode = payloadExtractor.extractProductNode(result.payload());
            if (productNode.isEmpty()) {
                logger.info("Supervisor product A2UI productDetail not found sessionId={}, taskId={}",
                        context.getSessionId(), result.taskId());
                continue;
            }
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
