package com.example.springsupervisorai.service.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring AI의 {@link PromptTemplate}을 사용하여 변수를 치환하여 supervisor 프롬프트 템플릿을 렌더링한다.
 * <p>
 * 템플릿 구문이 유효하지 않을 때 디버깅을 돕기 위해 템플릿 렌더링 오류에 진단 정보(잘린 템플릿 미리보기)를 포함한다.
 */
@Component
public class SupervisorPromptRenderService {

    /**
     * 주어진 변수로 프롬프트 템플릿 문자열을 렌더링한다.
     *
     * @param template 프롬프트 템플릿 문자열 (Spring AI 템플릿 구문)
     * @param variables 템플릿에 치환할 변수 맵
     * @return 렌더링된 프롬프트 문자열
     * @throws IllegalArgumentException 템플릿이 null이거나 해결되지 않은 플레이스홀더가 있는 경우
     */
    public String render(String template, Map<String, Object> variables) {
        try {
            return new PromptTemplate(template).render(variables);
        } catch (IllegalArgumentException ex) {
            String preview = template == null ? "null" : template
                    .replace("\n", "\\n")
                    .substring(0, Math.min(template.length(), 240));
            throw new IllegalArgumentException(
                    "Invalid supervisor prompt template. templatePreview=" + preview,
                    ex
            );
        }
    }
}
