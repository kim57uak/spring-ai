package com.example.springai.service.parser;

import com.example.springai.service.llm.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

abstract class AbstractResponseParser implements ResponseParser {

    protected final ObjectMapper objectMapper;

    protected AbstractResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected abstract Logger logger();

    protected abstract String providerName();

    protected final String parseText(CheckedSupplier<String> parser) {
        try {
            String parsed = parser.get();
            if (parsed != null && !parsed.isBlank()) {
                return parsed;
            }
            throw new IllegalStateException("Failed to parse " + providerName() + " response content");
        } catch (Exception e) {
            logger().error("Failed to parse {} response", providerName(), e);
            throw new IllegalStateException("Failed to parse " + providerName() + " response", e);
        }
    }

    protected final String parseStream(CheckedSupplier<String> parser, String fallbackOnError) {
        try {
            String parsed = parser.get();
            return parsed == null ? "" : parsed;
        } catch (Exception e) {
            logger().error("{} stream parse error", providerName(), e);
            return fallbackOnError;
        }
    }

    @FunctionalInterface
    protected interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
