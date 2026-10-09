package com.contactcenter.domain.email;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja JPA mapująca tabelę {@code email_message} (schemat z V010__create_email_social.sql).
 *
 * <p>Klucz główny jest ZŁOŻONY: {@code (message_id, message_at)} ({@link EmailMessageId}), bo tabela
 * jest partycjonowana RANGE po {@code message_at} (V102, DB-067 / BE-134). {@code message_id} nadaje
 * kod Java przed zapisem (natywny INSERT w {@link EmailMessageRepository#save}, bez
 * {@code @GeneratedValue}). {@code message_at} jest ustawiane RAZ przy tworzeniu wiadomości:
 * INBOUND = INTERNALDATE serwera IMAP ({@code Message#getReceivedDate()}), OUTBOUND = czas wysłania;
 * nie ma settera zmieniającego wartość po zapisie (kolumna partycjonująca, niemodyfikowalna w DB).
 *
 * <p>Nagłówek RFC 2822 Message-ID przechowywany jest w kolumnie {@code message_id_header}.
 *
 * <p>Pola {@code to_address}, {@code cc_address} i {@code bcc_address} są TEXT –
 * mogą zawierać listę adresów rozdzielonych przecinkami (format RFC 2822).
 *
 * <p>Brak kolumny {@code is_deleted} – ta tabela nie stosuje soft delete.
 * Brak kolumny {@code updated_at} – wiadomości email są immutable po zapisie.
 */
@Entity
@Table(name = "email_message")
@IdClass(EmailMessageId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailMessage {

    /** Część klucza złożonego ({@link EmailMessageId}); nadawana w Java przed INSERT. */
    @Id
    @Column(name = "message_id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Część klucza złożonego i klucz partycjonowania (V102). Ustawiane jawnie przy tworzeniu:
     * INBOUND = {@code Message#getReceivedDate()} (INTERNALDATE), NIE nagłówek {@code Date} nadawcy;
     * OUTBOUND = ten sam {@code Instant}, który trafia do {@code sent_at}. Brak settera — wartość
     * jest niemodyfikowalna po zapisie (zmiana przeniosłaby wiersz między partycjami i złamała
     * unikalność nagłówka D4).
     */
    @Id
    @Setter(AccessLevel.NONE)
    @Column(name = "message_at", nullable = false, updatable = false)
    private Instant messageAt;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * FK do tabeli {@code contact} (partycjonowanej).
     * Egzekwowane na poziomie aplikacji – PostgreSQL nie wspiera FK do tabeli partycjonowanej.
     * Może być null gdy wiadomość nie jest jeszcze przypisana do kontaktu.
     */
    @Column(name = "contact_id")
    private UUID contactId;

    /**
     * Kierunek wiadomości: INBOUND (przychodzące) lub OUTBOUND (wychodzące).
     * Mapowane jako VARCHAR po migracji V025 (typ contact_direction → VARCHAR + CHECK).
     */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    /** Nadawca wiadomości (format RFC 2822: "Name <email@domain.com>" lub "email@domain.com"). */
    @Column(name = "from_address", nullable = false, length = 255)
    private String fromAddress;

    /** Odbiorcy w polu To (może być lista rozdzielona przecinkami – RFC 2822). */
    @Column(name = "to_address", nullable = false, columnDefinition = "TEXT")
    private String toAddress;

    /** Odbiorcy w polu CC (opcjonalne). */
    @Column(name = "cc_address", columnDefinition = "TEXT")
    private String ccAddress;

    /** Odbiorcy w polu BCC (opcjonalne). */
    @Column(name = "bcc_address", columnDefinition = "TEXT")
    private String bccAddress;

    /** Temat wiadomości (maks. 998 znaków wg RFC 2822). */
    @Column(name = "subject", length = 998)
    private String subject;

    /** Treść wiadomości w formacie HTML (bez limitu). */
    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    /** Treść wiadomości w formacie plain text (bez limitu). */
    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    /**
     * RFC 2822 Message-ID header – unikalny identyfikator wiadomości w świecie email.
     * Format: {@code <unique@domain>}.
     * Używany do deduplikacji przy wielokrotnym pobieraniu IMAP.
     */
    @Column(name = "message_id_header", length = 255)
    private String messageIdHeader;

    /**
     * RFC 2822 In-Reply-To – Message-ID wiadomości, na którą odpowiadamy.
     * Używany do budowania wątków emailowych.
     */
    @Column(name = "in_reply_to", length = 255)
    private String inReplyTo;

    /**
     * Metadane załączników jako JSON.
     * Format: [{"filename": "...", "content_type": "...", "size_bytes": N, "s3_key": "..."}]
     * — klucz obiektu S3 to {@code s3_key} (nie {@code s3_url}); zob. {@code EmailAttachmentKeys}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    @Builder.Default
    private String attachments = "[]";

    /** Dla INBOUND: czas odebrania przez system (INTERNALDATE = {@link #messageAt}). */
    @Column(name = "received_at")
    private Instant receivedAt;

    /** Dla OUTBOUND: czas wysłania przez system (= {@link #messageAt}). */
    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * Status dostarczenia (dla OUTBOUND).
     * Wartości: PENDING, SENT, DELIVERED, BOUNCED, FAILED.
     */
    @Column(name = "delivery_status", length = 30)
    private String deliveryStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (attachments == null) {
            attachments = "[]";
        }
    }

    // =========================================================================
    // Enum kierunku wiadomości
    // =========================================================================

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    // =========================================================================
    // Enum statusu dostarczenia
    // =========================================================================

    public enum DeliveryStatus {
        PENDING,
        SENT,
        DELIVERED,
        BOUNCED,
        FAILED
    }
}
