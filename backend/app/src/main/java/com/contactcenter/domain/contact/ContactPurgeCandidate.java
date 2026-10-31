package com.contactcenter.domain.contact;

import java.time.Instant;
import java.util.UUID;

/**
 * Kandydat do usunięcia w retencji CONTACT_INTERACTIONS na ścieżce z włączonym usuwaniem wiadomości
 * (BE-126, EPIC-30, flaga {@code retention.purge.delete-messages=true}).
 *
 * <p>Niesie {@code startedAt} obok {@code contactId} z dwóch powodów: (1) {@code started_at} jest
 * kolumną partycjonowania tabeli {@code contact} — deterministyczny porządek
 * {@code ORDER BY started_at, contact_id} w {@code ContactRepository#findContactIdsOlderThan}
 * wymaga obu kolumn ({@code started_at} sam w sobie nie jest unikalny — dwa kontakty mogą mieć
 * identyczny znacznik czasu co do mikrosekundy); (2) ta sama para służy jako kursor stronicowania
 * keyset między iteracjami pętli purge w {@code RetentionPurgeServiceImpl} — patrz uzasadnienie
 * strategii H-1 (head-of-line blocking, code review BE-125) w Javadoc
 * {@code ContactRepository#findContactIdsOlderThan}.
 *
 * @param contactId UUID kontaktu
 * @param startedAt kolumna partycjonowania {@code contact.started_at} tego kontaktu
 */
public record ContactPurgeCandidate(UUID contactId, Instant startedAt) {}
