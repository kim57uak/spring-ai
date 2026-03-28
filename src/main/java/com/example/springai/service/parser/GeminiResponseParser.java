package com.example.springai.service.parser;

import com.example.springai.model.GeminiResponse;
import com.example.springai.service.llm.ResponseParser;
import com.example.springai.service.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini API 응답 파싱 구현체
 * SRP(Single Responsibility Principle) 준수
 */
@Component
public class GeminiResponseParser implements ResponseParser {

    private static final Logger logger = LoggerFactory.getLogger(GeminiResponseParser.class);
    private static final Pattern TEXT_PATTERN = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final ObjectMapper objectMapper;

    public GeminiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String extractText(String response) {
        try {
            GeminiResponse geminiResponse = objectMapper.readValue(response, GeminiResponse.class);
            if (geminiResponse.getCandidates() != null && !geminiResponse.getCandidates().isEmpty()) {
                GeminiResponse.Candidate candidate = geminiResponse.getCandidates().get(0);
                if (candidate.getContent() != null
                        && candidate.getContent().getParts() != null
                        && !candidate.getContent().getParts().isEmpty()) {
                    String text = candidate.getContent().getParts().get(0).getText();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
            throw new IllegalStateException("Failed to parse Gemini response content");
        } catch (Exception e) {
            logger.error("Failed to parse Gemini response", e);
            throw new IllegalStateException("Failed to parse Gemini response", e);
        }
    }

    @Override
    public String extractStreamText(String chunk) {
        try {
            Matcher matcher = TEXT_PATTERN.matcher(chunk);
            StringBuilder parsed = new StringBuilder();
            while (matcher.find()) {
                String text = matcher.group(1);
                parsed.append(JsonUtils.unescapeJson(text));
            }
            return parsed.toString();
        } catch (Exception e) {
            logger.error("Gemini stream parse error", e);
            return "";
        }
    }
}
