package com.contactcenter.domain.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium transkrypcji rozmów telefonicznych (tabela {@code contact_transcription}).
 *
 * <p>Transkrypcja jest tworzona przez Whisper po zapisaniu nagrania w S3.
 * Przechowywana osobno od pola {@code notes} w tabeli {@code contact},
 * które jest zarezerwowane dla ręcznych notatek agentów.
 *
 * <p>Wzorzec multi-tenant: rozszerza {@link TenantAwareRepository} – wywołuje
 * {@code assertSameTenant()} przed zapisem oraz {@code setTenantContextInDb()} przed każdym zapytaniem.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ContactTranscriptionRepository extends TenantAwareRepository {

    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Wstawia nowy rekord transkrypcji dla kontaktu.
     *
     * <p>Nie sprawdza duplikatów – jeden kontakt może mieć wiele transkrypcji
     * (np. po ponownym przetworzeniu nagrania). Odczyt przez {@link #findContentByContactId}
     * zawsze zwraca najnowszą.
     *
     * @param contactId UUID kontaktu (musi istnieć w tabeli {@code contact})
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @param content   pełna transkrypcja rozmowy
     * @param language  wykryty język ISO 639-1 (np. "pl", "en") – może być null
     */
    @Transactional
    public void save(UUID contactId, UUID tenantId, String content, String language) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        jdbcTemplate.update("""
                INSERT INTO contact_transcription (contact_id, tenant_id, content, language)
                VALUES (?::uuid, ?::uuid, ?, ?)
                """,
                contactId.toString(),
                tenantId.toString(),
                content,
                language);

        log.info("[ContactTranscriptionRepo] Zapisano transkrypcję: contactId={}, language={}, length={}",
                contactId, language, content.length());
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Zwraca treść najnowszej transkrypcji dla wskazanego kontaktu.
     *
     * <p>Gdy kontakt ma więcej niż jedną transkrypcję (np. po ponownym przetworzeniu),
     * zwracana jest ta z największą wartością {@code created_at}.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @return Optional z treścią transkrypcji lub empty() gdy brak rekordu
     */
    @Transactional(readOnly = true)
    public Optional<String> findContentByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<String> results = jdbcTemplate.queryForList("""
                SELECT content
                FROM contact_transcription
                WHERE contact_id = ?::uuid
                  AND tenant_id  = ?::uuid
                ORDER BY created_at DESC
                LIMIT 1
                """,
                String.class,
                contactId.toString(),
                tenantId.toString());

        log.debug("[ContactTranscriptionRepo] findContentByContactId: contactId={}, found={}",
                contactId, !results.isEmpty());

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
