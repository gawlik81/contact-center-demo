package com.contactcenter.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared (per test JVM) real Redis on Testcontainers for tests of Redis-backed logic. Tests MUST use
 * random tenant/user ids - data survives between test classes.
 */
public final class RedisTestContainer {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory factory;

    private RedisTestContainer() {
    }

    private static synchronized void start() {
        if (container == null) {
            TestcontainersSupport.ensureDockerApiVersion();
            GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            started.start();
            LettuceConnectionFactory f = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(started.getHost(), started.getMappedPort(6379)));
            f.afterPropertiesSet();
            container = started;
            factory = f;
        }
    }

    /** New template on the shared Redis. */
    public static synchronized StringRedisTemplate stringTemplate() {
        start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
