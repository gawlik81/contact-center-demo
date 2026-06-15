package com.contactcenter.domain.email;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link EmailMessage}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie musi poprzedzać
 * wywołanie {@link #setTenantContextInDb()} aby RLS działało poprawnie.
 *
 * <p>Deduplicacja po {@code message_id_header} chroni przed wielokrotnym zapisem
 * tej samej wiadomości przy wielokrotnym pollingu IMAP.
 */
@Slf4j
@Repository
@Transactional
public class EmailMessageRepository extends TenantAwareRepository {

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje nową wiadomość email.
     *
     * @param message encja do zapisania – {@code tenantId} musi być ustawione
     * @return zapisana encja z wygenerowanym {@code id}
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public EmailMessage save(EmailMessage message) {
        assertSameTenant(message.getTenantId());
        setTenantContextInDb();
        return em.merge(message);
    }

    /**
     * Aktualizuje istniejącą wiadomość email.
     *
     * @param message encja do aktualizacji
     * @return zaktualizowana encja
     */
    public EmailMessage update(EmailMessage message) {
        assertSameTenant(message.getTenantId(), message.getId());
        setTenantContextInDb();
        return em.merge(message);
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wiadomość po UUID (PK).
     *
     * @param messageId UUID wiadomości (kolumna message_id)
     * @return Optional z wiadomością lub empty
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findById(UUID messageId) {
        setTenantContextInDb();
        return Optional.ofNullable(em.find(EmailMessage.class, messageId));
    }

    /**
     * Wyszukuje wiadomość po nagłówku RFC 2822 Message-ID (deduplikacja IMAP).
     *
     * @param messageIdHeader wartość nagłówka Message-ID, np. {@code <abc@domain.com>}
     * @param tenantId        UUID tenanta (dla RLS + UNIQUE constraint)
     * @return Optional z wiadomością lub empty jeśli brak duplikatu
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findByMessageIdHeader(String messageIdHeader, UUID tenantId) {
        setTenantContextInDb(tenantId);
        List<EmailMessage> results = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.messageIdHeader = :header AND m.tenantId = :tenantId",
                        EmailMessage.class)
                .setParameter("header", messageIdHeader)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera pierwszą wiadomość INBOUND powiązaną z kontaktem (root kontaktu email).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z wiadomością lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findFirstInboundByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        List<EmailMessage> results = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId " +
                        "AND m.direction = 'INBOUND' " +
                        "ORDER BY m.receivedAt ASC NULLS LAST",
                        EmailMessage.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera historię wiadomości dla kontaktu (paginacja).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param pageable  parametry paginacji
     * @return strona wiadomości posortowanych po {@code received_at DESC}
     */
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByContactId(UUID contactId, UUID tenantId, Pageable pageable) {
        setTenantContextInDb(tenantId);

        List<EmailMessage> content = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId " +
                        "ORDER BY m.receivedAt DESC NULLS LAST, m.createdAt DESC",
                        EmailMessage.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM EmailMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId",
                        Long.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Pobiera listę wiadomości w wątku emailowym (pogrupowane po In-Reply-To).
     *
     * <p>Wątek identyfikowany jest przez pierwotny {@code messageIdHeader}.
     * Zwraca oryginalną wiadomość + wszystkie odpowiedzi (In-Reply-To łańcuch).
     *
     * @param originalMessageIdHeader nagłówek Message-ID wiadomości oryginalnej
     * @param tenantId                UUID tenanta
     * @param pageable                parametry paginacji
     * @return strona wiadomości wątku posortowanych chronologicznie
     */
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByThreadRootMessageId(
            String originalMessageIdHeader, UUID tenantId, Pageable pageable) {
        setTenantContextInDb(tenantId);

        // Wątek = oryginalna wiadomość + wszystkie z In-Reply-To = oryginalny Message-ID
        List<EmailMessage> content = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.tenantId = :tenantId " +
                        "AND (m.messageIdHeader = :rootId OR m.inReplyTo = :rootId) " +
                        "ORDER BY m.createdAt ASC",
                        EmailMessage.class)
                .setParameter("tenantId", tenantId)
                .setParameter("rootId", originalMessageIdHeader)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM EmailMessage m " +
                        "WHERE m.tenantId = :tenantId " +
                        "AND (m.messageIdHeader = :rootId OR m.inReplyTo = :rootId)",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .setParameter("rootId", originalMessageIdHeader)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Pobiera paginowaną listę wiadomości dla tenanta (widok listy).
     *
     * @param pageable parametry paginacji
     * @return strona wiadomości posortowanych po {@code created_at DESC}
     */
    @Transactional(readOnly = true)
    public Page<EmailMessage> findAll(Pageable pageable) {
        setTenantContextInDb();
        UUID tenantId = currentTenantId();

        List<EmailMessage> content = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.tenantId = :tenantId " +
                        "ORDER BY m.createdAt DESC",
                        EmailMessage.class)
                .setParameter("tenantId", tenantId)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM EmailMessage m WHERE m.tenantId = :tenantId",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }
}
