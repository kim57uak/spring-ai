package com.example.springai.service.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Deque;

/**
 * 세션별 대화 기록 관리
 * SRP(Single Responsibility Principle) 준수 - 메모리 관리 책임만 담당
 */
@Component
public class SessionMemoryManager {

    private static final int MAX_SESSION_MESSAGES = 30;
    private final Map<String, Deque<Message>> sessionMemories = new ConcurrentHashMap<>();

    /**
     * 세션 메모리를 가져오거나 새로 생성
     */
    private Deque<Message> getOrCreate(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, id -> new ArrayDeque<>());
    }

    /**
     * 메시지 추가
     */
    public void addMessage(String sessionId, Message message) {
        Deque<Message> memory = getOrCreate(sessionId);
        synchronized (memory) {
            memory.addLast(message);
            trimIfNeeded(memory);
        }
    }

    /**
     * 읽기 전용 스냅샷 반환
     */
    public List<Message> getSnapshot(String sessionId) {
        Deque<Message> memory = getOrCreate(sessionId);
        synchronized (memory) {
            return List.copyOf(memory);
        }
    }

    /**
     * 메모리가 최대 크기를 초과하면 오래된 메시지 제거
     */
    private void trimIfNeeded(Deque<Message> memory) {
        if (memory.size() > MAX_SESSION_MESSAGES) {
            while (memory.size() > MAX_SESSION_MESSAGES) {
                memory.pollFirst();
            }
        }
    }

    /**
     * 세션 메모리 삭제
     */
    public void clear(String sessionId) {
        sessionMemories.remove(sessionId);
    }

    /**
     * 세션의 메시지 개수 조회
     */
    public int getMessageCount(String sessionId) {
        Deque<Message> memory = sessionMemories.get(sessionId);
        if (memory == null) {
            return 0;
        }
        synchronized (memory) {
            return memory.size();
        }
    }
}
