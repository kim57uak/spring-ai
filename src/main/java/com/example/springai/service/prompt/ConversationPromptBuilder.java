package com.example.springai.service.prompt;

import com.example.springai.service.memory.Message;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 대화형 프롬프트 빌더 구현체
 * SRP(Single Responsibility Principle) 준수 - 프롬프트 생성 책임만 담당
 */
@Component
public class ConversationPromptBuilder implements PromptBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E) HH:mm:ss");
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public String buildPrompt(List<Message> history, String currentMessage) {
        String contextPrompt = buildContextFromHistory(history, currentMessage);
        return addTimestamp(contextPrompt);
    }

    /**
     * 대화 히스토리를 컨텍스트로 변환
     */
    private String buildContextFromHistory(List<Message> messages, String currentMessage) {
        if (messages.size() <= 1) {
            return currentMessage;
        }

        StringBuilder context = new StringBuilder();
        context.append("이전 대화:\n");

        // 마지막 메시지(현재 유저 메시지)를 제외한 히스토리
        for (int i = 0; i < messages.size() - 1; i++) {
            Message msg = messages.get(i);
            if ("user".equals(msg.role())) {
                context.append("User: ").append(msg.content()).append("\n");
            } else if ("assistant".equals(msg.role())) {
                context.append("Assistant: ").append(msg.content()).append("\n");
            }
        }

        context.append("\n현재 질문: ").append(currentMessage);
        context.append("\n\n위 대화 맥락을 고려하여 답변해주세요.");

        return context.toString();
    }

    /**
     * 현재 시간 정보를 프롬프트에 추가
     */
    private String addTimestamp(String message) {
        LocalDateTime now = LocalDateTime.now(KOREA_ZONE);
        String dateTimeInfo = now.format(DATE_TIME_FORMATTER);
        return String.format("[현재 시간: %s (한국시간)]\n%s", dateTimeInfo, message);
    }
}
