package com.contactcenter.domain.contact;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja reprezentująca podsumowanie AI wygenerowane dla kontaktu (BE-089).
 *
 * <p>Przechowywana w osobnej tabeli {@code contact_ai_summary}, co pozwala
 * na historię podsumowań dla jednego kontaktu (np. po ponownym generowaniu)
 * oraz odciąża partycjonowaną tabelę {@code contact}.
 *
 * <p>Wartości wszystkich pól ustawiane są explicite w {@link com.contactcenter.domain.contact.AiSummaryService}
 * – brak {@code @PrePersist}. Zapis odbywa się przez natywny INSERT w
 * {@link com.contactcenter.domain.contact.ContactAiSummaryRepository}.
 */
@Entity
@Table(name = "contact_ai_summary")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class ContactAiSummary {

    /** PK – generowane przez serwis przed zapisem. */
    @Id
    @Column(name = "ai_summary_id")
    private UUID aiSummaryId;

    /** FK → contact.contact_id. Brak @ManyToOne – contact jest w tabeli partycjonowanej. */
    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    /** UUID tenanta – zapewnia izolację multi-tenant. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Wygenerowana treść podsumowania AI. */
    @Column(name = "summary", columnDefinition = "TEXT", nullable = false)
    private String summary;

    /** Nazwa modelu AI użytego do generowania (np. "gpt-4o", "claude-3-5-sonnet"). */
    @Column(name = "model", length = 100, nullable = false)
    private String model;

    /** Czas wygenerowania podsumowania przez model AI. */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** Czas zapisu rekordu do bazy (ustawiany przez serwis). */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
