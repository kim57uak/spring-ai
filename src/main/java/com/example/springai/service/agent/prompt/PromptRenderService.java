package com.example.springai.service.agent.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PromptRenderService {

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
