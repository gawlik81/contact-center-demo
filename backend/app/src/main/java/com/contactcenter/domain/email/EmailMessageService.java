package com.contactcenter.domain.email;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zapewniający odczyt wiadomości email ({@link EmailMessage}) dla
 * konsumentów cross-domain ({@code domain.contact}) oraz dla {@code api.email.EmailController}.
 *
 * <p>Stanowi fasadę nad {@link EmailMessageRepository} – wszystkie metody są
 * prostym przekazaniem do repozytorium, bez dodatkowej logiki biznesowej.
 */
public interface EmailMessageService {

    /**
     * Pobiera wiadomość po UUID (PK).
     *
     * @param messageId UUID wiadomości (kolumna message_id)
     * @return Optional z wiadomością lub empty
     */
    Optional<EmailMessage> findById(UUID messageId);

    /**
     * Pobiera historię wiadomości dla kontaktu (paginacja).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param pageable  parametry paginacji
     * @return strona wiadomości posortowanych po {@code received_at DESC}
     */
    Page<EmailMessage> findByContactId(UUID contactId, UUID tenantId, Pageable pageable);

    /**
     * Pobiera pierwszą wiadomość INBOUND powiązaną z kontaktem (root kontaktu email).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z wiadomością lub empty gdy brak
     */
    Optional<EmailMessage> findFirstInboundByContactId(UUID contactId, UUID tenantId);

    /**
     * Pobiera listę wiadomości w wątku emailowym (pogrupowane po In-Reply-To).
     *
     * @param originalMessageIdHeader nagłówek Message-ID wiadomości oryginalnej
     * @param tenantId                UUID tenanta
     * @param pageable                parametry paginacji
     * @return strona wiadomości wątku posortowanych chronologicznie
     */
    Page<EmailMessage> findByThreadRootMessageId(String originalMessageIdHeader, UUID tenantId, Pageable pageable);

    /**
     * Pobiera paginowaną listę wiadomości dla bieżącego tenanta (widok listy).
     *
     * @param pageable parametry paginacji
     * @return strona wiadomości posortowanych po {@code created_at DESC}
     */
    Page<EmailMessage> findAll(Pageable pageable);
}
