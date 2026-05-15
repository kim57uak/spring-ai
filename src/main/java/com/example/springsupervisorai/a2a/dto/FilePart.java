package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 파일 데이터를 표현하는 message part. mimeType, fileData, uri 중 하나를 사용한다.
 */
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
