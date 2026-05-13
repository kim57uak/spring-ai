package com.example.springai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilePart(
        String mimeType,
        Object fileData,
        String uri
) implements Part {
    public FilePart(String mimeType, Object fileData) {
        this(mimeType, fileData, null);
    }

    public FilePart(String mimeType, String uri) {
        this(mimeType, null, uri);
    }
}
