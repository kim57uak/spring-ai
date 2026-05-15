package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;

import java.util.Optional;

/**
 * 소유한 템플릿 뷰를 광고하고 해당 도메인의 실제 렌더 로직을 처리하는 도메인별 A2UI 빌더.
 * <p>
 * {@link CompositeSupervisorA2uiService}는 등록된 도메인 서비스를 순회하며
 * 주어진 뷰를 {@link #supports(SupervisorPlanningContext, A2uiTemplateView)}하는 서비스를 찾는다.
 */
public interface SupervisorA2uiDomainService {

    /**
     * 이 도메인 서비스가 컨텍스트에 대해 주어진 템플릿 뷰를 빌드할 수 있는지 확인한다.
     *
     * @param context 현재 planning 컨텍스트
     * @param selectedView 확인할 템플릿 뷰
     * @return 이 서비스가 뷰를 렌더링할 수 있으면 true
     */
    boolean supports(SupervisorPlanningContext context, A2uiTemplateView selectedView);

    /**
     * 선택된 뷰에 대한 A2UI 렌더 결과를 빌드한다.
     *
     * @param context 현재 planning 컨텍스트
     * @param selectedView 렌더링할 템플릿 뷰
     * @param message 선택적 재정의 메시지
     */
    Optional<SupervisorA2uiService.A2uiRenderResult> build(
            SupervisorPlanningContext context,
            A2uiTemplateView selectedView,
            String message
    );
}
