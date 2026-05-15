package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;

import java.util.List;

/**
 * 제품 도메인 A2UI 템플릿의 계약.
 * <p>
 * 각 템플릿은 표현하는 {@link A2uiTemplateView}, 해당 뷰의 기본 표시 메시지,
 * 요약 핵심 필드 필요 여부, 렌더링할 UI 섹션 식별자의 정렬된 목록을 정의한다.
 */
public interface ProductA2uiTemplate {

    /**
     * 이 템플릿과 연결된 A2UI 템플릿 뷰 상수를 반환한다.
     *
     * @return 템플릿 뷰 식별자
     */
    A2uiTemplateView view();

    /**
     * 주어진 제품명에 대한 기본 표시 메시지를 반환한다.
     *
     * @param productName 표시할 제품명
     * @return 기본 메시지 문자열
     */
    String defaultMessage(String productName);

    /**
     * 이 템플릿이 요약 핵심 필드(가격, 일정, 공지)의 존재를 요구하는지 여부.
     *
     * @return 요약 핵심 필드가 필요하면 true
     */
    boolean requiresSummaryCoreFields();

    /**
     * 렌더링할 UI 섹션 식별자의 정렬된 목록을 반환한다.
     *
     * @return 정렬된 섹션 ID 목록
     */
    List<String> rootChildren();
}
