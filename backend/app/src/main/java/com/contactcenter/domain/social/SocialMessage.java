package com.contactcenter.domain.social;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encja JPA mapująca tabelę {@code social_message} (schemat z V010__create_email_social.sql,
 * partycjonowana RANGE po {@code sent_at} od V100/DB-065).
 *
 * <p>Kolumna {@code platform} używa typu ENUM {@code social_platform} – zachowanego
 * dla tabeli social_message (podobnie jak social_integration, nie konwertowanego do VARCHAR).
 *
 * <p>Pole {@code attachments} przechowuje metadane załączników jako JSONB array.
 * Format: [{"url": "...", "type": "image", "name": "..."}]
 *
 * <p>Wiadomości social media są immutable po zapisie (brak updated_at).
 *
 * <p><strong>BE-132 (EPIC-30):</strong> od V100 tabela jest partycjonowana RANGE po {@code sent_at},
 * co wymaga – analogicznie do {@code ContactEvent}/{@code ContactEventId} (BE-117) – klucza złożonego
 * {@code (message_id, sent_at)} obsługiwanego przez {@link SocialMessageId}. PostgreSQL wymaga, żeby
 * kolumna partycjonowania wchodziła w PRIMARY KEY. {@code messageId} NIE jest już generowany przez
 * Hibernate ({@code @GeneratedValue}) – jest nadawany w Javie ({@code UUID.randomUUID()}) PRZED
 * zapisem, zawsze przez natywny INSERT w {@link SocialMessageRepository#save} (koniec {@code em.merge},
 * które nie wspiera klucza złożonego na partycjonowanej tabeli). Usunięto {@code @PrePersist} (martwy
 * kod dla ścieżki natywnego INSERT – callbacki cyklu życia JPA nie są wywoływane dla
 * {@code createNativeQuery}) – wypełnianie domyślnych wartości ({@code createdAt}, {@code attachments},
 * fallback {@code sentAt}) przeniesione do {@link SocialMessageRepository#save}, jedynego punktu zapisu.
 */
@Entity
@Table(name = "social_message")
@IdClass(SocialMessageId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialMessage {

    /**
     * Nadawany w Javie ({@code UUID.randomUUID()}) PRZED zapisem – patrz klasa Javadoc. Celowo BEZ
     * {@code @GeneratedValue}/{@code @UuidGenerator}: na tabeli partycjonowanej z kluczem złożonym
     * strategia generowania Hibernate dla pojedynczej kolumny nie ma zastosowania.
     */
    @Id
    @Column(name = "message_id", updatable = false, nullable = false)
    private UUID messageId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * FK do tabeli {@code contact} (partycjonowanej).
     * Egzekwowane na poziomie aplikacji – PostgreSQL nie wspiera FK do tabeli partycjonowanej.
     */
    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    /**
     * FK do tabeli {@code social_integration} – integracja, przez którą nadeszła wiadomość.
     * ON DELETE SET NULL – wiadomość pozostaje gdy integracja zostanie usunięta.
     */
    @Column(name = "integration_id")
    private UUID integrationId;

    /**
     * Platforma społecznościowa: FACEBOOK, INSTAGRAM, WHATSAPP.
     * Mapowane jako ENUM {@code social_platform} (zachowany typ DB, nie VARCHAR).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, columnDefinition = "social_platform")
    private SocialPlatform platform;

    /**
     * Kierunek wiadomości: INBOUND (przychodząca) lub OUTBOUND (wychodząca).
     * Mapowane jako VARCHAR po migracji V025 (contact_direction → VARCHAR + CHECK).
     */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    /** Unikalny identyfikator wiadomości na platformie (dla idempotentności). */
    @Column(name = "external_message_id", nullable = false, length = 255)
    private String externalMessageId;

    /** ID nadawcy na platformie (user_id Facebook, WhatsApp phone number itp.). */
    @Column(name = "sender_external_id", length = 255)
    private String senderExternalId;

    /** Treść wiadomości tekstowej. */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * Metadane załączników jako JSONB array.
     * Format: [{"url": "...", "type": "image", "name": "..."}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    @Builder.Default
    private String attachments = "[]";

    /**
     * Czas wysłania wiadomości na platformie – kolumna partycjonowania (V100/DB-065), NOT NULL.
     * Jest częścią klucza głównego {@link SocialMessageId}.
     *
     * <p><strong>BE-132:</strong> musi być deterministyczny (czas zdarzenia z payloadu platformy),
     * NIE czas przetworzenia webhooka ({@code Instant.now()} w chwili odbioru) – inaczej redelivery
     * tego samego zdarzenia dostaje inny {@code sent_at} i unikalność złożona {@code (tenant_id,
     * external_message_id, sent_at)} nie wykrywa duplikatu. Patrz
     * {@code SocialWebhookController#parseFacebookEvent}/{@code parseInstagramEvent}/
     * {@code parseWhatsAppMessage}.
     */
    @Id
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /** Czas odebrania przez system (dla INBOUND). */
    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // =========================================================================
    // Enum kierunku wiadomości
    // =========================================================================

    public enum Direction {
        INBOUND,
        OUTBOUND
    }
}
