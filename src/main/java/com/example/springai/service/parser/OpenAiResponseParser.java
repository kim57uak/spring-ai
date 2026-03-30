package com.example.springai.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI API 응답 파싱 구현체
 * SRP(Single Responsibility Principle) 준수
 */
@Component
public class OpenAiResponseParser extends AbstractResponseParser {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiResponseParser.class);

    public OpenAiResponseParser(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    protected Logger logger() {
        return logger;
    }

    @Override
    protected String providerName() {
        return "OpenAI";
    }

    @Override
    public String extractText(String response) {
        return parseText(() -> {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    String text = content.asText();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
            return "";
        });
    }

    @Override
    public String extractStreamText(String chunk) {
        return parseStream(() -> {
            StringBuilder parsed = new StringBuilder();
            String[] lines = chunk.split("\n");
            boolean consumedSse = false;

            for (String line : lines) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                consumedSse = true;

                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data) || data.isEmpty()) {
                    continue;
                }

                JsonNode root = objectMapper.readTree(data);
                parsed.append(extractChunkContent(root));
            }

            // Some providers/proxies can return raw JSON chunks without "data:" prefix.
            if (!consumedSse && chunk.trim().startsWith("{")) {
                JsonNode root = objectMapper.readTree(chunk.trim());
                parsed.append(extractChunkContent(root));
            }
            return parsed.toString();
        }, "");
    }

    private String extractChunkContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }

        JsonNode first = choices.get(0);
        JsonNode delta = first.path("delta");
        JsonNode deltaContent = delta.path("content");

        if (deltaContent.isTextual()) {
            return deltaContent.asText();
        }
        if (deltaContent.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : deltaContent) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                    continue;
                }
                JsonNode text = part.path("text");
                if (!text.isMissingNode() && !text.isNull()) {
                    builder.append(text.asText());
                }
            }
            return builder.toString();
        }

        JsonNode messageContent = first.path("message").path("content");
        if (messageContent.isTextual()) {
            return messageContent.asText();
        }
        return "";
    }
}
