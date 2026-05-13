package com.example.springsupervisorai.a2a.dto;

import java.util.Map;

public record DataPart(Map<String, Object> data) implements Part {
    public DataPart {
        if (data == null) {
            data = Map.of();
        }
    }
}
