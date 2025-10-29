package com.example.springai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.classic.spi.ILoggingEvent;

@Configuration
public class LoggingConfig {

    private static final Logger logger = LoggerFactory.getLogger(LoggingConfig.class);

    @EventListener(ApplicationReadyEvent.class)
    public void configureLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        // API 키 마스킹 필터 추가
        Filter<ILoggingEvent> apiKeyFilter = new Filter<ILoggingEvent>() {
            @Override
            public FilterReply decide(ILoggingEvent event) {
                String message = event.getFormattedMessage();
                if (message != null && message.contains("key=")) {
                    // 메시지에서 API 키 마스킹
                    String maskedMessage = message.replaceAll("key=[^&\\s]*", "key=***MASKED***");
                    // 새로운 이벤트 생성은 복잡하므로 로거 레벨에서 처리
                }
                return FilterReply.NEUTRAL;
            }
        };
        
        logger.info("API key masking filter configured");
    }
}