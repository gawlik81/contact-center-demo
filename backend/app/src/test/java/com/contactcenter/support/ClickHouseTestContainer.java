package com.contactcenter.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared (per test JVM) real ClickHouse on Testcontainers (same image as docker-compose), reaped by Ryuk at JVM end.
 * Offers an empty {@code contact_center_dw} database only - no DW schema is applied.
 */
public final class ClickHouseTestContainer {

    public static final String DATABASE = "contact_center_dw";

    private static GenericContainer<?> container;

    private ClickHouseTestContainer() {
    }

    private static synchronized GenericContainer<?> container() {
        if (container == null) {
            TestcontainersSupport.ensureDockerApiVersion();
            GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:24.3"))
                    .withExposedPorts(8123)
                    .withEnv("CLICKHOUSE_USER", "default")
                    .withEnv("CLICKHOUSE_PASSWORD", "")
                    .withEnv("CLICKHOUSE_DB", DATABASE)
                    .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123));
            started.start();
            container = started;
        }
        return container;
    }

    /** JDBC URL of the {@value #DATABASE} database. */
    public static String jdbcUrl() {
        GenericContainer<?> c = container();
        return "jdbc:clickhouse://" + c.getHost() + ":" + c.getMappedPort(8123) + "/" + DATABASE;
    }
}
