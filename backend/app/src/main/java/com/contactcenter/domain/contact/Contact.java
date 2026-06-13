package com.contactcenter.domain.contact;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encja reprezentująca jeden kontakt (interakcję) z klientem końcowym.
 *
 * <p>Mapuje tabelę {@code contact} (schemat z V007__create_contact.sql).
 * Tabela jest partycjonowana RANGE po {@code started_at} (miesięcznie), co wymaga
 * specjalnej obsługi JPA:
 * <ul>
 *   <li>Klucz główny to para {@code (contact_id, started_at)} – obsługiwana przez {@link ContactId}.</li>
 *   <li>Zapis przez natywny SQL ({@code ContactRepository#insert}), ponieważ PostgreSQL
 *       nie obsługuje standardowych JPA INSERT na tabelach partycjonowanych z PK zawierającym
 *       kolumnę partycjonowania.</li>
 *   <li>Aktualizacja przez natywny UPDATE (PATCH semantics).</li>
 *   <li>Odczyt przez JPQL działa poprawnie – Hibernate odpytuje tabelę nadrzędną.</li>
 * </ul>
 *
 * <p>Pole {@code channelMetadata} przechowywane jako JSONB i mapowane przez
 * {@link JdbcTypeCode}(SqlTypes.JSON) – Hibernate 6 obsługuje PGobject ↔ Map natywnie.
 *
 * <p>Kolumna {@code duration_seconds} jest obliczana przez trigger bazy danych
 * {@code fn_contact_on_update} przy ustawieniu {@code ended_at}.
 *
 * <p><strong>WAŻNE – brak callbacków JPA lifecycle:</strong>
 * Ta klasa NIE definiuje {@code @PrePersist} ani {@code @PreUpdate}, ponieważ
 * tabela jest partycjonowana. Zapis odbywa się wyłącznie przez natywny INSERT
 * ({@code ContactRepository#insert}), a aktualizacja przez natywny UPDATE
 * ({@code ContactRepository#update}) – oba omijają mechanizm JPA lifecycle callbacków.
 * Wszystkie pola (createdAt, startedAt, queuedAt, status, channelMetadata) muszą być
 * ustawiane explicite w warstwie serwisowej ({@code ContactService}) przed wywołaniem
 * metod repozytorium.
 */
@Entity
@Table(name = "contact")
@IdClass(ContactId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @Column(name = "contact_id", nullable = false, updatable = false)
    private UUID contactId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** FK do customer (nullable – klient nieznany do momentu identyfikacji). */
    @Column(name = "customer_id")
    private UUID customerId;

    /** FK do app_user (agent). NULL gdy kontakt jest jeszcze w kolejce lub porzucony. */
    @Column(name = "agent_id")
    private UUID agentId;

    /** Kolejka, z której pochodzi kontakt. */
    @Column(name = "queue_id")
    private UUID queueId;

    /** Kampania outbound. NULL dla kontaktów inbound. */
    @Column(name = "campaign_id")
    private UUID campaignId;

    /**
     * Kanał komunikacji.
     * Wartości: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP.
     * Przechowywany jako VARCHAR (V019 skonwertowało ENUMy do VARCHAR + CHECK).
     */
    @Column(name = "channel", nullable = false, length = 50)
    private String channel;

    /**
     * Kierunek kontaktu.
     * Wartości: INBOUND, OUTBOUND.
     */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    /**
     * Status cyklu życia kontaktu.
     * Wartości: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED.
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "QUEUED";

    /** Numer CLI (telefon) lub email nadawcy – szybki lookup bez JOIN do customer. */
    @Column(name = "remote_address", length = 255)
    private String remoteAddress;

    /** Czas umieszczenia w kolejce. Wymagany dla KPI ASA. */
    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    /** Czas przypisania do agenta. Delta (queued_at, assigned_at) = czas oczekiwania. */
    @Column(name = "assigned_at")
    private Instant assignedAt;

    /**
     * Czas rozpoczęcia kontaktu – klucz partycjonowania, NOT NULL.
     * Jest częścią klucza głównego {@link ContactId}.
     */
    @Id
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Czas zakończenia kontaktu. NULL dla kontaktów aktywnych. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * Czas trwania w sekundach. Obliczany przez trigger {@code fn_contact_on_update}
     * przy ustawieniu {@code ended_at}. NULL dla aktywnych kontaktów.
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Wynik kontaktu ustawiany przez agenta po zakończeniu (np. SALE, DECLINED, CALLBACK). */
    @Column(name = "disposition_code", length = 50)
    private String dispositionCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** URL nagrania w S3-compatible storage. NULL jeśli brak nagrania. */
    @Column(name = "recording_url", columnDefinition = "TEXT")
    private String recordingUrl;

    /**
     * Metadane kanałowe (JSONB).
     * Przykład: {"sip_call_id": "abc123", "email_message_id": "<msg@example.com>"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_metadata", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> channelMetadata = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * UUID callbacku (scheduled_callback.callback_id), z którego powstał ten kontakt.
     * Nullable – brak FK ze względu na composite PK tabeli contact.
     * Wypełniany przez DialerService przy tworzeniu kontaktu dla realizacji callbacku.
     */
    @Column(name = "callback_id")
    private UUID callbackId;

    /**
     * UUID kontaktu źródłowego (contact.contact_id), z którego powstał ten kontakt
     * w wyniku transferu kolejkowego.
     * Nullable – brak FK ze względu na composite PK tabeli contact.
     * Wypełniany przez TwilioTelephonyAdapter przy tworzeniu nowego kontaktu po transferze.
     */
    @Column(name = "transferred_from_contact_id")
    private UUID transferredFromContactId;

    /**
     * UUID rekordu listy kampanii (campaign_contact.record_id), z którego powstał ten kontakt.
     * Nullable – wypełniany tylko dla kontaktów wychodzących inicjowanych przez dialer kampanijny.
     * Brak FK ze względu na composite PK w campaign_contact (analogicznie do callback_id).
     * Dodany w migracji V063.
     */
    @Column(name = "campaign_contact_record_id")
    private UUID campaignContactRecordId;

}
