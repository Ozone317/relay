package com.example.relay.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.support.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class RedisConnectivitySmokeTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void stringRedisTemplate_canSetAndGet() {
        redisTemplate.opsForValue().set("smoke-test-key", "smoke-test-value");

        assertEquals("smoke-test-value", redisTemplate.opsForValue().get("smoke-test-key"));
    }
}
