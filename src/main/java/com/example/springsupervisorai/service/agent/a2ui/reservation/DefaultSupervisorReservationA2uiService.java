package com.example.springsupervisorai.service.agent.a2ui.reservation;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiDomainService;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Owns reservation-domain A2UI screens and keeps reservation input assembly outside product templates.
 */
@Component
public class DefaultSupervisorReservationA2uiService implements SupervisorA2uiDomainService {

    private final ObjectMapper objectMapper;
    private final ReservationPayloadExtractor payloadExtractor;
    private final ReservationA2uiMessageBuilder messageBuilder;

    public DefaultSupervisorReservationA2uiService(
            ObjectMapper objectMapper,
            ReservationPayloadExtractor payloadExtractor,
            ReservationA2uiMessageBuilder messageBuilder
    ) {
        this.objectMapper = objectMapper;
        this.payloadExtractor = payloadExtractor;
        this.messageBuilder = messageBuilder;
    }

    @Override
    public boolean supports(SupervisorPlanningContext context, A2uiTemplateView selectedView) {
        if (selectedView != A2uiTemplateView.PACKAGE_RESERVATION_FORM) {
            return false;
        }
        if (context == null) {
            return false;
        }
        return hasReservationRoutingPlan(context)
                || (context.getResults() != null && context.getResults().stream().anyMatch(this::isSupportedResult));
    }

    @Override
    public Optional<SupervisorA2uiService.A2uiRenderResult> build(
            SupervisorPlanningContext context,
            A2uiTemplateView selectedView,
            String message
    ) {
        ReservationPresentationModel model = extractSeed(context);
        if (model == null) {
            return Optional.empty();
        }
        try {
            String resolvedMessage = message == null || message.isBlank()
                    ? "요청에 맞는 입력 화면을 준비했습니다."
                    : message;
            String surfaceId = "reservation-form-" + context.getSessionId();
            return Optional.of(new SupervisorA2uiService.A2uiRenderResult(
                    resolvedMessage,
                    objectMapper.writeValueAsString(messageBuilder.build(surfaceId, model))
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private ReservationPresentationModel extractSeed(SupervisorPlanningContext context) {
        if (context == null) {
            return null;
        }
        if (context.getResults() != null) {
            for (DownstreamCallResult result : context.getResults()) {
                if (!isSupportedResult(result)) {
                    continue;
                }
                Optional<ReservationPresentationModel> extracted = payloadExtractor.extract(result, context.getUserMessage());
                if (extracted.isPresent()) {
                    return extracted.get();
                }
            }
        }
        Optional<ReservationPresentationModel> extracted = payloadExtractor.extract(null, context.getUserMessage());
        if (extracted.isPresent()) {
            return extracted.get();
        }
        if (hasReservationRoutingPlan(context)) {
            return new ReservationPresentationModel("", "", "", "1");
        }
        return null;
    }

    private boolean hasReservationRoutingPlan(SupervisorPlanningContext context) {
        return context != null
                && context.getRoutingPlans() != null
                && context.getRoutingPlans().stream().anyMatch(plan -> "reservation".equalsIgnoreCase(plan.agentKey()));
    }

    private boolean isSupportedResult(DownstreamCallResult result) {
        if (result == null) {
            return false;
        }
        String agentKey = result.agentKey() == null ? "" : result.agentKey();
        if (!"product".equalsIgnoreCase(agentKey) && !"reservation".equalsIgnoreCase(agentKey)) {
            return false;
        }
        return DownstreamResultInterpreter.assess(result).outcome() == DownstreamResultInterpreter.Outcome.SUCCESS;
    }
}
