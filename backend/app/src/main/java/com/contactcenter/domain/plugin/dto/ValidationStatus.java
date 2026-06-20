package com.contactcenter.domain.plugin.dto;

/**
 * Wynik pipeline'u walidacji JAR-a pluginu ({@code PluginValidationService.validate}).
 *
 * <p>Ten ticket (BE-098) zwraca wyłącznie {@link #VALIDATED} (sukces) lub {@link #REJECTED}
 * (porażka na którymkolwiek etapie) — {@code PENDING_REVIEW} (manualny review przed
 * aktywacją, gdy wymagane jest podpisywanie) jest świadomie odłożone razem z logiką
 * podpisu (OQ-28-1, EPIC-28-PLAN.md) i nie jest zwracane przez tę implementację.
 */
public enum ValidationStatus {
    VALIDATED,
    REJECTED
}
