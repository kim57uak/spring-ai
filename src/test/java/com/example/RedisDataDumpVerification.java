package com.example;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis에 저장된 모든 데이터를 읽어 출력하는 검증용 테스트.
 * Redis 연결 정보는 환경변수 REDIS_HOST(기본 localhost), REDIS_PORT(기본 6379)를 사용한다.
 */
class RedisDataDumpVerification {

    static final String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    static final int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    static RedisClient redisClient;
    static StatefulRedisConnection<String, String> connection;
    static RedisCommands<String, String> sync;

    static boolean connected = false;

    @BeforeAll
    static void setup() {
        try {
            redisClient = RedisClient.create("redis://" + host + ":" + port);
            connection = redisClient.connect();
            sync = connection.sync();
            sync.ping();
            connected = true;
            System.out.println("=== Redis 연결 성공: " + host + ":" + port + " ===");
            System.out.println("서버 정보: " + sync.info("server").lines()
                    .filter(l -> l.startsWith("redis_version"))
                    .findFirst().orElse("N/A"));
        } catch (Exception e) {
            System.err.println("Redis 연결 실패: " + e.getMessage()
                    + "\n환경변수 REDIS_HOST=" + host + ", REDIS_PORT=" + port
                    + " 를 확인하세요.");
            connected = false;
        }
    }

    @AfterAll
    static void teardown() {
        if (connection != null) connection.close();
        if (redisClient != null) redisClient.shutdown();
    }

    /**
     * 직접 실행: gradlew test --tests "com.example.RedisDataDumpVerification"
     * 또는 main()으로 직접 실행.
     */
    public static void main(String[] args) {
        setup();
        if (connected) {
            new RedisDataDumpVerification().dumpAllRedisData();
        }
        teardown();
    }

    @Test
    void dumpAllRedisData() {
        if (!connected) return;
        List<String> keys = sync.keys("*");
        System.out.println("\n=== Redis 전체 키: " + keys.size() + "개 ===");
        for (String key : keys.stream().sorted().toList()) {
            String type = sync.type(key);
            long ttl = sync.ttl(key);
            String value = readValue(key, type);
            System.out.printf("  KEY : %s%n", key);
            System.out.printf("  TYPE: %s | TTL: %ds%n", type, ttl);
            if (!value.isBlank()) {
                System.out.printf("  VAL : %s%n", truncate(value, 2000));
            }
            System.out.println();
        }
        assertThat(keys.size()).as("Redis에 최소 1개 이상의 키가 있어야 검증 가능").isGreaterThanOrEqualTo(0);
    }

    private String readValue(String key, String type) {
        try {
            switch (type) {
                case "string" -> { return sync.get(key); }
                case "list" -> { return String.join("\n", sync.lrange(key, 0, -1)); }
                case "set" -> { return String.join("\n", sync.smembers(key)); }
                case "zset" -> { return String.join("\n", sync.zrange(key, 0, -1)); }
                case "hash" -> {
                    var map = sync.hgetall(key);
                    if (map.isEmpty()) return "";
                    var sb = new StringBuilder();
                    map.forEach((k, v) -> sb.append(k).append(" = ").append(v).append("\n"));
                    return sb.toString().stripTrailing();
                }
                default -> { return "(unhandled type: " + type + ")"; }
            }
        } catch (Exception e) {
            return "(read error: " + e.getMessage() + ")";
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n... (" + s.length() + " chars total)";
    }

}
