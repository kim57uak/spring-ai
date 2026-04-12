package com.example.springsupervisorai.service.agent.store;

import java.util.Optional;

public interface GraphCheckpointStore {

    Optional<String> loadCheckpoint(String sessionId);

    void saveCheckpoint(String sessionId, String payload);

    void clear(String sessionId);
}

