package com.example.springai.service.agent.store;

import java.util.Optional;

public interface GraphCheckpointStore {
    Optional<String> loadCheckpoint(String sessionId);
    void saveCheckpoint(String sessionId, String payload);
    void clear(String sessionId);
}
