package com.example.springai.model.agent;

public record AgentChatChunk(
        String sessionId,
        ChunkType type,
        String content
) {
}
