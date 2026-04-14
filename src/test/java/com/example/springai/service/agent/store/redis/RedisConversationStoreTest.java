package com.example.springai.service.agent.store.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConversationStoreTest {

    @Test
    void saveLoadAndClearUsesLocalFallbackWhenRedisIsUnavailable() {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        RedisConversationStore store = new RedisConversationStore(redisProvider, new ObjectMapper());

        store.save("session-1", List.of("a", "b"));

        assertThat(store.load("session-1")).containsExactly("a", "b");

        store.clear("session-1");

        assertThat(store.load("session-1")).isEmpty();
    }

    @Test
    void saveNullMessagesStoresEmptyList() {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        RedisConversationStore store = new RedisConversationStore(redisProvider, new ObjectMapper());

        store.save("session-2", null);

        assertThat(store.load("session-2")).isEmpty();
    }
}
