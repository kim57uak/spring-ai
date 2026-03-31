package com.example.springai.service.agent.prompt;

import com.example.springai.model.agent.PlanningContext;

/**
 * 에이전트 최종 응답 생성용 프롬프트 템플릿 생성 계약.
 */
public interface PromptTemplateService {

    /**
     * 계획/도구 결과/히스토리를 반영한 compose 프롬프트를 생성한다.
     */
    String buildComposePrompt(PlanningContext context);
}
