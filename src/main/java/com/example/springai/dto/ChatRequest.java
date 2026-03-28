package com.example.springai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 4000, message = "message must be <= 4000 characters")
        String message,
        String model
) {
    public ChatRequest(
            String message,
            String model
    ) {
        this.message = message;
        this.model = model;
    }

    public ChatRequest(String message) {
        this(message, "mistral");
    }
}
