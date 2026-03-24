package com.contactcenter.api.customer;

/**
 * Strategia deduplikacji podczas importu CSV klientów (BE-026).
 *
 * <ul>
 *   <li>{@link #SKIP}      – pomiń wiersz gdy klient z tym samym phone lub email już istnieje.</li>
 *   <li>{@link #OVERWRITE} – zaktualizuj istniejącego klienta danymi z CSV.</li>
 * </ul>
 */
public enum DeduplicationMode {
    SKIP,
    OVERWRITE
}
