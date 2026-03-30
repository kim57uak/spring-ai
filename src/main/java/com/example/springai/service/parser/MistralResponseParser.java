package com.example.springai.service.parser;

import com.example.springai.model.MistralResponse;
import com.example.springai.service.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mistral API 응답 파싱 구현체
 * SRP(Single Responsibility Principle) 준수
 */
@Component
public class MistralResponseParser extends AbstractResponseParser {

    private static final Logger logger = LoggerFactory.getLogger(MistralResponseParser.class);
    private static final Pattern CONTENT_PATTERN = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public MistralResponseParser(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    protected Logger logger() {
        return logger;
    }

    @Override
    protected String providerName() {
        return "Mistral";
    }

    @Override
    public String extractText(String response) {
        return parseText(() -> {
            MistralResponse mistralResponse = objectMapper.readValue(response, MistralResponse.class);
            if (mistralResponse.getChoices() != null && !mistralResponse.getChoices().isEmpty()) {
                MistralResponse.Choice choice = mistralResponse.getChoices().get(0);
                if (choice.getMessage() != null && choice.getMessage().getContent() != null
                        && !choice.getMessage().getContent().isBlank()) {
                    return choice.getMessage().getContent();
                }
            }
            return "";
        });
    }

    @Override
    public String extractStreamText(String chunk) {
        return parseStream(() -> {
            Matcher matcher = CONTENT_PATTERN.matcher(chunk);
            StringBuilder parsed = new StringBuilder();
            while (matcher.find()) {
                String content = matcher.group(1);
                parsed.append(JsonUtils.unescapeJson(content));
            }
            if (parsed.length() > 0) {
                return parsed.toString();
            }
            if (chunk.contains("[DONE]") || chunk.contains("created")) {
                return "";
            }
            if (chunk.contains("\"error\"")) {
                return chunk;
            }
            return "";
        }, chunk == null ? "" : chunk);
    }
}
