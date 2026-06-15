package com.contactcenter.domain.gdpr;

import jakarta.persistence.EntityNotFoundException;

import java.util.UUID;

/**
 * Serwis realizujący operacje RODO (BE-031).
 *
 * <p>Implementuje dwa prawa podmiotów danych:
 * <ul>
 *   <li>Art. 20 – Prawo do przenoszenia danych: {@link #exportCustomerData(UUID)} tworzy
 *       archiwum ZIP zawierające dane klienta, historię kontaktów i log audytowy.</li>
 *   <li>Art. 17 – Prawo do bycia zapomnianym: {@link #anonymizeCustomer(UUID)} usuwa PII
 *       klienta, usuwa nagrania z S3 i oznacza rekord jako is_deleted=true.</li>
 * </ul>
 *
 * <p>Obie operacje logowane są w AUDIT_LOG przez {@code AuditLogService} (fire-and-forget).
 */
public interface GdprService {

    /**
     * Eksportuje wszystkie dane klienta do archiwum ZIP (RODO Art. 20).
     *
     * <p>Archiwum zawiera:
     * <ul>
     *   <li>{@code customer.json} – dane profilu klienta (PII)</li>
     *   <li>{@code contacts.json} – pełna historia kontaktów</li>
     *   <li>{@code audit_log.json} – metadane zdarzenia eksportu</li>
     * </ul>
     *
     * <p>Pliki audio nagrań nie są dołączane – dostęp przez presigned URL (BE-010).
     *
     * @param customerId UUID klienta
     * @return bajty archiwum ZIP
     * @throws EntityNotFoundException gdy klient nie istnieje w tenancie
     * @throws GdprServiceImpl.GdprException gdy nie uda się zbudować archiwum ZIP
     */
    byte[] exportCustomerData(UUID customerId);

    /**
     * Anonimizuje dane osobowe klienta zgodnie z RODO Art. 17 (prawo do bycia zapomnianym).
     *
     * <p>Sekwencja operacji:
     * <ol>
     *   <li>Pobierz listę kluczy S3 nagrań klienta.</li>
     *   <li>Usuń nagrania z S3 (best-effort – błąd nie przerywa anonimizacji).</li>
     *   <li>Anonimizuj rekord klienta w DB (first_name='ANONYMIZED', last_name='ANONYMIZED',
     *       phone=[], email=[], is_deleted=true).</li>
     *   <li>Zaloguj zdarzenie GDPR_ANONYMIZE w AUDIT_LOG.</li>
     * </ol>
     *
     * <p>Rekord customer pozostaje w bazie – historia kontaktów zachowana dla celów
     * rozliczeniowych (dane nie zawierają już PII po anonimizacji).
     *
     * @param customerId UUID klienta do anonimizacji
     * @throws EntityNotFoundException gdy klient nie istnieje lub jest już zanonimizowany
     */
    void anonymizeCustomer(UUID customerId);
}
