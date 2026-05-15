package com.example.springsupervisorai.service.agent.store.redis;

import com.example.springsupervisorai.common.redis.RedisTtlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConversationStoreTest {

    private static final RedisTtlPolicy ttlPolicy = new RedisTtlPolicy();

    @Test
    void saveLoadAndClearUsesLocalFallbackWhenRedisIsUnavailable() {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        RedisConversationStore store = new RedisConversationStore(redisProvider, new ObjectMapper(), ttlPolicy);

        store.save("session-1", List.of("hello", "world"));

        assertThat(store.load("session-1")).containsExactly("hello", "world");

        store.clear("session-1");

        assertThat(store.load("session-1")).isEmpty();
    }
}
