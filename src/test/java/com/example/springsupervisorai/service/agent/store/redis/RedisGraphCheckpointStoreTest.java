package com.example.springsupervisorai.service.agent.store.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisGraphCheckpointStoreTest {

    @Test
    void saveLoadAndClearUsesLocalFallbackWhenRedisIsUnavailable() {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        RedisGraphCheckpointStore store = new RedisGraphCheckpointStore(redisProvider);

        store.saveCheckpoint("session-1", "{\"step\":\"COMPOSE\"}");

        assertThat(store.loadCheckpoint("session-1")).contains("{\"step\":\"COMPOSE\"}");

        store.clear("session-1");

        assertThat(store.loadCheckpoint("session-1")).isEmpty();
    }
}
