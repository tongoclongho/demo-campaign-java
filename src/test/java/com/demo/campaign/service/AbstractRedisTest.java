package com.demo.campaign.service;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton {@code redis:latest} container shared by every service test that needs real Redis command
 * semantics (ZADD score ordering, SPOP, DECR returning the post-value, SADD returning 0 on a
 * duplicate). Started once per JVM in the static initialiser and reaped by Ryuk, so it is not tied to
 * a single test class lifecycle.
 *
 * <p>Docker must be running.
 */
abstract class AbstractRedisTest {

    protected static final StringRedisTemplate redisTemplate;

    static {
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:latest"))
                .withExposedPorts(6379);
        redis.start();

        var connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void flushRedis() {
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
