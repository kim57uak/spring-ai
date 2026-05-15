package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;

/**
 * Compose 단계에 지원되는 A2UI 템플릿 뷰를 알리는 도메인 제공자.
 * <p>
 * Compose 중 LLM은 사용자 의도에 가장 적합한 템플릿 뷰를 결정한다.
 * 이 인터페이스는 다음을 제공한다:
 * <ul>
 *   <li>{@link #supports(SupervisorPlanningContext)} — 이 도메인이 관련 있는지 여부</li>
 *   <li>{@link #supportedTemplateKeys()} — 가시성을 위한 쉼표로 구분된 템플릿 키</li>
 *   <li>{@link #templateCatalogPrompt()} — 각 템플릿과 사용 시기를 설명하는 자연어 카탈로그</li>
 * </ul>
 */
public interface A2uiComposePromptProvider {

    boolean supports(SupervisorPlanningContext context);

    String supportedTemplateKeys();

    String templateCatalogPrompt();
}
