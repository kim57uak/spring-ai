package com.example.springai.service.agent.store;

import java.util.List;

public interface ConversationStore {
    List<String> load(String sessionId);
    void save(String sessionId, List<String> messages);
    void clear(String sessionId);
}
