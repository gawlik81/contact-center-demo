package com.contactcenter.domain.contact;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
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
 *
 * <p>Tabela nie ma odpowiadającej encji JPA – czysty {@link JdbcTemplate}. Od migracji V086
 * (DB-050) jest partycjonowana RANGE po {@code created_at} (PK złożony
 * {@code (transcription_id, created_at)}), więc nie jest reprezentowana przez {@code @IdClass}
 * (BE-117 dotyczy wyłącznie encji JPA – {@code ContactEvent}/{@code ContactAiSummary}). Repozytorium
 * nie posiada operacji UPDATE/DELETE adresujących wiersz po PK – tylko {@link #save} (INSERT)
 * i odczyt po {@code contact_id} – więc kolumna partycjonowania nie musi występować w żadnym WHERE.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class ContactTranscriptionRepository extends TenantAwareRepository {

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
     * <p><strong>BE-117:</strong> {@code created_at} jest ustawiane jawnie z Javy (zamiast
     * polegać wyłącznie na {@code DEFAULT NOW()} w bazie), żeby wartość widoczna w encji
     * po stronie Javy (np. do logowania, korelacji z innymi zdarzeniami tego samego kontaktu)
     * zawsze zgadzała się co do mikrosekundy z wartością faktycznie zapisaną w partycjonowanej
     * kolumnie {@code created_at} – istotne przy operacjach wsadowych, gdzie poleganie na
     * DEFAULT dawałoby każdemu wierszowi nieznaczną, ale realną rozbieżność w stosunku do
     * czasu wygenerowania rekordu w Javie.
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

        Instant createdAt = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO contact_transcription (contact_id, tenant_id, content, language, created_at)
                VALUES (?::uuid, ?::uuid, ?, ?, ?)
                """,
                contactId.toString(),
                tenantId.toString(),
                content,
                language,
                Timestamp.from(createdAt));

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

    // =========================================================================
    // BE-113: Retencja – usuwanie batchowane (EPIC-29)
    // =========================================================================

    /**
     * Usuwa batch transkrypcji tenanta starszych niż {@code cutoff} (retencja EPIC-29, BE-113 –
     * kategoria TRANSCRIPTS).
     *
     * <p>Identyfikuje wiersze do usunięcia przez pełny klucz główny {@code (transcription_id,
     * created_at)} – NIE przez fizyczny {@code ctid} (patrz szczegółowe uzasadnienie w
     * {@code ContactRepository#deleteBatchOlderThan}: {@code ctid} nie jest unikalny globalnie na
     * tabeli partycjonowanej). {@code created_at} jest kolumną partycjonowania (V086/DB-050).
     *
     * @param tenantId  UUID tenanta
     * @param cutoff    granica czasowa – usuwane są transkrypcje z {@code created_at < cutoff}
     * @param batchSize maksymalna liczba wierszy usuwanych w jednym wywołaniu (rozmiar {@code LIMIT})
     * @return liczba usuniętych wierszy (0 = brak kwalifikujących się wierszy)
     */
    @Transactional
    public int deleteBatchOlderThan(UUID tenantId, Instant cutoff, int batchSize) {
        setTenantContextInDb(tenantId);

        int deleted = jdbcTemplate.update("""
                WITH batch AS (
                    SELECT transcription_id, created_at FROM contact_transcription
                    WHERE tenant_id = ?::uuid AND created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                )
                DELETE FROM contact_transcription c
                USING batch b
                WHERE c.transcription_id = b.transcription_id AND c.created_at = b.created_at
                """,
                tenantId.toString(),
                Timestamp.from(cutoff),
                batchSize);

        log.info("[ContactTranscriptionRepo] Purge batch: tenant={}, cutoff={}, usunięto={}", tenantId, cutoff, deleted);
        return deleted;
    }
}
