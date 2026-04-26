package com.contactcenter.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Walidacja polityk RLS (Row-Level Security) przy starcie aplikacji.
 *
 * <p>Sprawdza czy krytyczne tabele multi-tenant mają zdefiniowane polityki RLS
 * w PostgreSQL. Brak polityki RLS na tabeli oznacza ryzyko wycieku danych
 * między tenantami jeśli {@code set_tenant_context()} nie zostanie wywołana
 * przed zapytaniem.
 *
 * <p><strong>WAŻNE:</strong> Brak RLS loguje tylko WARNING – nie blokuje startu.
 * Środowiska testowe mogą nie mieć RLS (np. Testcontainers z uproszczonym schematem).
 * W środowisku produkcyjnym monitoring alertów WARN powinien wykryć problem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RlsValidationService {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void validateRlsPolicies() {
        try {
            List<String> tablesWithoutRls = jdbcTemplate.queryForList(
                """
                SELECT t.tablename
                FROM pg_tables t
                WHERE t.schemaname = 'public'
                  AND t.tablename NOT IN (
                      SELECT DISTINCT tablename FROM pg_policies WHERE schemaname = 'public'
                  )
                  AND t.tablename IN (
                      'app_user', 'queue', 'campaign', 'customer',
                      'contact', 'email_message', 'social_message',
                      'scheduled_callback', 'audit_log', 'ivr_tree', 'agent_break'
                  )
                """,
                String.class
            );

            if (!tablesWithoutRls.isEmpty()) {
                log.warn("[RlsValidation] Tabele bez polityki RLS: {}. " +
                         "Dane mogą być widoczne przez wszystkich tenantów jeśli set_tenant_context() nie zostanie wywołana.",
                         tablesWithoutRls);
            } else {
                log.info("[RlsValidation] Wszystkie krytyczne tabele mają polityki RLS.");
            }
        } catch (Exception e) {
            // Nie blokuj startu aplikacji przy błędzie walidacji (np. brak uprawnień do pg_policies)
            log.warn("[RlsValidation] Nie można zweryfikować polityk RLS: {}", e.getMessage());
        }
    }
}
