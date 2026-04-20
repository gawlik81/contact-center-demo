package com.contactcenter.infrastructure.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Konfiguracja DataSource dla ClickHouse (BE-030b).
 *
 * <p>Aktywowana gdy {@code etl.dw.type=clickhouse}. Tworzy dedykowany
 * {@link DataSource} dla ClickHouse, niezależny od głównego DataSource PostgreSQL.
 *
 * <p>Używa {@link DriverManagerDataSource} (bez poolingu) – ClickHouse jest
 * odporne na wielokrotne połączenia i nie wymaga Hikari dla operacji batchowych ETL.
 * Liczba połączeń ETL jest niska (jeden wątek schedulera).
 *
 * <p>URL musi być w formacie: {@code jdbc:clickhouse://host:8123/database}
 * – port 8123 to HTTP interface ClickHouse (używany przez JDBC driver).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "etl.dw.type", havingValue = "clickhouse")
public class ClickHouseDataSourceConfig {

    @Bean("clickhouseDataSource")
    public DataSource clickhouseDataSource(
            @Value("${spring.datasource.clickhouse.url}") String url,
            @Value("${spring.datasource.clickhouse.username:default}") String username,
            @Value("${spring.datasource.clickhouse.password:}") String password
    ) {
        log.info("[ClickHouse] Tworzenie DataSource: url={}", url);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);

        return ds;
    }
}
