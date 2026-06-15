package com.contactcenter.domain.phonenumber;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja JPA reprezentująca numer telefonu tenanta (tabela {@code phone_number}).
 *
 * <p>Mapuje schemat z V039. Numer musi być zgodny z formatem E.164 –
 * walidacja odbywa się zarówno na poziomie DB (CHECK constraint) jak i w serwisie.
 *
 * <p>Soft delete: logiczne usunięcie przez flagę {@code isDeleted = true}.
 * Fizyczny rekord pozostaje w DB do celów audytowych.
 */
@Entity
@Table(name = "phone_number")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneNumber {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "phone_number_id", updatable = false, nullable = false)
    private UUID phoneNumberId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Numer telefonu w formacie E.164, np. {@code +48221234567}. */
    @Column(name = "number", nullable = false, length = 20)
    private String number;

    /** Czytelna nazwa numeru, np. "Linia główna". Opcjonalne. */
    @Column(name = "display_name", length = 100)
    private String displayName;

    /** Flaga aktywności numeru – nieaktywny numer nie odbiera połączeń. */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Flaga soft delete – {@code true} oznacza logiczne usunięcie. */
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
