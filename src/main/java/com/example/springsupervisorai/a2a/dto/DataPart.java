package com.example.springsupervisorai.a2a.dto;

import java.util.Map;

/**
 * JSON 데이터를 표현하는 message part.
 */
public record DataPart(Map<String, Object> data) implements Part {
    public DataPart {
        if (data == null) {
            data = Map.of();
        }
    }
}
