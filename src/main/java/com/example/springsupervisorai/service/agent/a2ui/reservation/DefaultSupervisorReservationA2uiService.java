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
 * 예약 도메인 A2UI 화면을 소유하며 예약 입력 조립을 제품 템플릿 외부에서 관리한다.
 * <p>
 * downstream 결과나 사용자 메시지에서 직접 예약 시드 데이터를 추출하여
 * PACKAGE_RESERVATION_FORM 뷰를 지원한다. 예약 라우팅 계획은 있지만
 * 결과 데이터가 아직 없을 때는 빈 시드로 폴백한다.
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
        // 예약 라우팅 계획 또는 관련 downstream 결과가 있는 경우 지원
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
        // 각 지원되는 downstream 결과에서 추출 시도
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
        // 사용자 메시지에서만 추출하는 것으로 폴백
        Optional<ReservationPresentationModel> extracted = payloadExtractor.extract(null, context.getUserMessage());
        if (extracted.isPresent()) {
            return extracted.get();
        }
        // 라우팅 계획이 있으면 빈 시드 반환 (데이터는 나중에 도착)
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
