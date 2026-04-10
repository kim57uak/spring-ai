package com.example.springai.service.agent.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 프롬프트 템플릿 렌더링을 담당하는 유틸리티 컴포넌트.
 * <p>
 * 템플릿 치환 실패 시 디버깅 가능한 예외 메시지를 제공한다.
 */
@Component
public class PromptRenderService {

    /**
     * 템플릿 변수 치환을 수행하고, 실패 시 디버깅 가능한 미리보기와 함께 예외를 던진다.
     * <p>
     * 에러 메시지에는 템플릿 앞부분 preview를 포함한다.
     */
    public String render(String template, Map<String, Object> variables) {
        try {
            return new PromptTemplate(template).render(variables);
        } catch (IllegalArgumentException ex) {
            String preview = template == null ? "null" : template
                    .replace("\n", "\\n")
                    .substring(0, Math.min(template.length(), 240));
            throw new IllegalArgumentException(
                    "Invalid prompt template. Check unescaped '{' or '}' in literal text. templatePreview=" + preview,
                    ex
            );
        }
    }
}
