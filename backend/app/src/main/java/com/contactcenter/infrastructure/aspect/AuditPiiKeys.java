package com.contactcenter.infrastructure.aspect;

import java.util.Set;

/**
 * Jedno źródło prawdy (Java) dla listy kluczy PII maskowanych w migawkach zapisywanych do
 * {@code audit_log} (BE-142, EPIC-30, decyzja D10 – wiersz audytowy zostaje, wartości wybranych
 * kluczy są zastępowane placeholderem {@link #MASK_PLACEHOLDER}, klucz pozostaje widoczny).
 *
 * <p><strong>KRYTYCZNE – lista MUSI być identyczna z listą w funkcji SQL
 * {@code fn_mask_pii_jsonb_value}</strong> (migracja
 * {@code V098__mask_audit_log_pii_on_anonymize_customer.sql}, wołana z {@code mask_audit_log_pii} /
 * {@code anonymize_customer}, DB-062). Dwa niezależne mechanizmy maskują tę samą treść:
 * <ul>
 *   <li>{@code AuditAspect} (ta klasa) – maskuje u źródła, dla NOWYCH wpisów audytowych
 *       zapisywanych podczas normalnej pracy aplikacji (CREATE/UPDATE encji CUSTOMER/CONTACT).</li>
 *   <li>{@code mask_audit_log_pii} (SQL, V098) – maskuje wstecznie wpisy HISTORYCZNE podmiotu przy
 *       anonimizacji RODO (Art. 17), wołane wewnętrznie przez {@code anonymize_customer}.</li>
 * </ul>
 * Rozjazd między listami oznaczałby, że część PII przecieka przez jeden z dwóch mechanizmów.
 * Spójność jest pilnowana testem {@code AuditPiiKeysSqlConsistencyTest}, który parsuje RZECZYWISTĄ
 * treść pliku migracji V098 z classpath (nie drugą, ręcznie utrzymywaną kopię listy).
 */
public final class AuditPiiKeys {

    /**
     * Zbiór kluczy JSON uznawanych za PII w migawkach {@code old_value}/{@code new_value}
     * encji CUSTOMER i CONTACT. Skopiowane 1:1 z komentarza funkcji {@code fn_mask_pii_jsonb_value}
     * (V098) – NIE modyfikować bez jednoczesnej, świadomej zmiany po stronie SQL.
     */
    public static final Set<String> KEYS = Set.of(
            "firstName", "lastName", "phone", "email", "customFields", "gdprConsent",
            "externalId", "remoteAddress", "channelMetadata", "notes", "recordingUrl",
            "fromAddress", "subject"
    );

    /**
     * Typy encji ({@link Audited#entityType()}), dla których maskowanie PII jest stosowane
     * (BE-142 Zakres p.1). Inne audytowane encje (TENANT, USER, QUEUE, EMAIL_TEMPLATE, RECORDING)
     * nie niosą tych samych pól PII i celowo NIE są objęte tym mechanizmem – rozszerzenie listy
     * wymaga świadomej decyzji (nowy klucz może kolidować z polem nie-PII innej encji).
     */
    public static final Set<String> MASKED_ENTITY_TYPES = Set.of("CUSTOMER", "CONTACT");

    /** Placeholder zastępujący wartość klucza PII – klucz JSON zostaje, treść znika. */
    public static final String MASK_PLACEHOLDER = "[MASKED]";

    private AuditPiiKeys() {
    }
}
