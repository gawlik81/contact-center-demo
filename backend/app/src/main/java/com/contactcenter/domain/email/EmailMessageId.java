package com.contactcenter.domain.email;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link EmailMessage}.
 *
 * <p>Tabela {@code email_message} jest partycjonowana RANGE po kolumnie {@code message_at}
 * (migracja V102, DB-067), dlatego klucz główny PostgreSQL musi zawierać obie kolumny:
 * {@code message_id} i {@code message_at}. Wzorzec 1:1 z {@code SocialMessageId} (BE-132)
 * i {@code ContactEventId} (BE-117).
 *
 * <p>Pełny klucz jest jedynym sposobem na lookup „po kluczu partycji" (partition pruning).
 * Lookup po samym {@code message_id} jest dozwolony tylko tam, gdzie wołający nie zna
 * {@code message_at} (np. endpointy REST z identyfikatorem w URL) — wtedy PostgreSQL przeszukuje
 * indeks PK każdej partycji (koszt: liczba partycji × indeks PK).
 *
 * <p>Pole {@code messageAt} jest niemodyfikowalne po zapisie (kolumna partycjonująca; trigger
 * BEFORE UPDATE w V102) — zmiana wartości przeniosłaby wiersz między partycjami.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class EmailMessageId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID id;
    private Instant messageAt;
}
