package com.contactcenter.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Explicit primary PostgreSQL DataSource definition.
 *
 * Needed because ClickHouseDataSourceConfig registers a DataSource bean before
 * Spring Boot's DataSourceAutoConfiguration runs, causing it to skip PostgreSQL
 * datasource creation (its @ConditionalOnMissingBean(DataSource.class) fires).
 * Flyway and JPA would then pick up the ClickHouse DataSource instead.
 */
@Configuration
public class PostgresDataSourceConfig {

    @Bean("dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password
    ) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }
}
