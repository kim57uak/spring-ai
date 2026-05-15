package com.example.springsupervisorai.service.agent.a2ui.common;

/**
 * Compose 단계에서 선택되어 도메인 빌더에 디스패치되는 표준 A2UI 템플릿 뷰.
 * <p>
 * 각 열거형 상수는 {@link SupervisorA2uiDomainService}가 렌더링할 수 있는
 * 고유한 UI 레이아웃을 나타낸다. Compose LLM은 사용자 의도와 사용 가능한
 * 데이터에 따라 적절한 뷰를 선택한다.
 */
public enum A2uiTemplateView {
    PACKAGE_SUMMARY,
    PACKAGE_PRICING,
    PACKAGE_TIMELINE,
    PACKAGE_BOOKING,
    PACKAGE_SALE_PRODUCT_CREATE_FORM,
    PACKAGE_RESERVATION_FORM
}
