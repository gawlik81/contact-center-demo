package com.contactcenter.domain.social;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link SocialMessage}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie musi poprzedzać
 * wywołanie {@link #setTenantContextInDb()} aby RLS działało poprawnie.
 *
 * <p>Deduplikacja opiera się na {@code external_message_id} + {@code tenant_id}
 * (constraint {@code uq_social_message_external_id} w DB).
 */
@Slf4j
@Repository
@Transactional
class SocialMessageRepository extends TenantAwareRepository {

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje nową wiadomość social media.
     *
     * @param message encja do zapisania – {@code tenantId} musi być ustawione
     * @return zapisana encja z wygenerowanym {@code messageId}
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public SocialMessage save(SocialMessage message) {
        assertSameTenant(message.getTenantId());
        setTenantContextInDb();
        log.debug("[SocialMessageRepo] Zapisuję wiadomość: externalId={}, platform={}, tenant={}",
                message.getExternalMessageId(), message.getPlatform(), message.getTenantId());
        return em.merge(message);
    }

    // =========================================================================
    // BE-113: Retencja – odcięcie referencji (EPIC-29)
    // =========================================================================

    /**
     * Zeruje {@code contact_id} dla wiadomości wskazujących na usuwane kontakty (retencja EPIC-29,
     * BE-113 – kategoria CONTACT_INTERACTIONS).
     *
     * <p>Kolumna {@code contact_id} jest nullable od V028. Metoda TYLKO odcina referencję (bulk
     * UPDATE) – NIE usuwa wierszy {@code social_message}, zgodnie z zakresem BE-113.
     *
     * <p>Bulk JPQL UPDATE (nie {@code em.merge}) – omija walidację {@code nullable=false} na
     * poziomie encji {@link SocialMessage#getContactId()} (adnotacja JPA dotyczy generowania DDL;
     * kolumna w bazie jest faktycznie nullable od V028).
     *
     * @param tenantId   UUID tenanta (RLS + cross-tenant safety)
     * @param contactIds lista UUID usuniętych kontaktów – pusta lista jest no-opem
     * @return liczba zaktualizowanych wierszy
     */
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }
        setTenantContextInDb(tenantId);

        int updated = em.createQuery(
                        "UPDATE SocialMessage m SET m.contactId = NULL " +
                        "WHERE m.tenantId = :tenantId AND m.contactId IN :contactIds")
                .setParameter("tenantId", tenantId)
                .setParameter("contactIds", contactIds)
                .executeUpdate();

        log.info("[SocialMessageRepo] Odcięto contact_id: tenant={}, kontaktów={}, wiadomości={}",
                tenantId, contactIds.size(), updated);
        return updated;
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera historię wiadomości dla kontaktu (do 50 wiadomości, posortowane od najnowszych).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return lista wiadomości posortowanych po {@code sent_at DESC}
     */
    @Transactional(readOnly = true)
    public List<SocialMessage> findByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        log.debug("[SocialMessageRepo] Pobieranie historii: contactId={}, tenant={}", contactId, tenantId);

        return em.createQuery(
                        "SELECT m FROM SocialMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId " +
                        "ORDER BY m.sentAt DESC",
                        SocialMessage.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(50)
                .getResultList();
    }

    /**
     * Wyszukuje wiadomość po zewnętrznym identyfikatorze (deduplikacja).
     *
     * @param externalMessageId identyfikator wiadomości na platformie
     * @param tenantId          UUID tenanta
     * @return Optional z wiadomością lub empty gdy brak duplikatu
     */
    @Transactional(readOnly = true)
    public Optional<SocialMessage> findByExternalMessageId(String externalMessageId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        log.debug("[SocialMessageRepo] Sprawdzam idempotentność: externalId={}, tenant={}", externalMessageId, tenantId);

        List<SocialMessage> results = em.createQuery(
                        "SELECT m FROM SocialMessage m " +
                        "WHERE m.externalMessageId = :externalId AND m.tenantId = :tenantId",
                        SocialMessage.class)
                .setParameter("externalId", externalMessageId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
