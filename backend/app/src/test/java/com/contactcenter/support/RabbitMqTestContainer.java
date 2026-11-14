package com.contactcenter.support;

import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared (per test JVM) real RabbitMQ on Testcontainers (same image as docker-compose). Started lazily and
 * reaped by Ryuk at JVM end, like {@link PostgresTestDatabase} and {@link RedisTestContainer}.
 */
public final class RabbitMqTestContainer {

    private static RabbitMQContainer container;

    private RabbitMqTestContainer() {
    }

    private static synchronized RabbitMQContainer container() {
        if (container == null) {
            TestcontainersSupport.ensureDockerApiVersion();
            RabbitMQContainer started = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));
            started.start();
            container = started;
        }
        return container;
    }

    public static String host() {
        return container().getHost();
    }

    public static int amqpPort() {
        return container().getAmqpPort();
    }

    public static String username() {
        return container().getAdminUsername();
    }

    public static String password() {
        return container().getAdminPassword();
    }
}
