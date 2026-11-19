package com.contactcenter.security;

/**
 * Stabilne kody błędów w ramce STOMP {@code ERROR} (BE-236) - KONTRAKT BE &lt;-&gt; FE (FE-171).
 *
 * <p>Kod jest przekazywany w natywnym nagłówku {@value #HEADER} ramki ERROR, niezależnie od brzmienia nagłówka
 * {@code message}. Nazw kodów i nazwy nagłówka NIE wolno zmieniać bez uzgodnienia z frontendem; dopuszczalne jest
 * wyłącznie dodawanie nowych kodów (klient musi traktować nieznany kod jak {@link #WS_INTERNAL}).
 * Opis kontraktu: {@code documentation/tech/04-backend.md}.
 */
public enum WsErrorCode {

    /** Destynacja/komenda niedozwolona dla roli lub tenanta (autoryzacja SUBSCRIBE/SEND, BE-229). */
    WS_FORBIDDEN,
    /**
     * Ramka wymagająca uwierzytelnionej sesji STOMP (SUBSCRIBE/SEND) wysłana bez wcześniejszego CONNECT z poprawnym
     * JWT. To BŁĄD PROTOKOŁU KLIENTA, a nie wygaśnięcie sesji/tokenu - NIE jest sygnałem do odświeżenia tokenu
     * (klient łączy się ponownie z backoffem).
     */
    WS_NO_SESSION,
    /** CONNECT/STOMP bez tokenu JWT. */
    WS_TOKEN_MISSING,
    /**
     * Token JWT wygasł (claim {@code exp}). Zgłaszany WYŁĄCZNIE przy CONNECT/STOMP: wygaśnięcie tokenu w trakcie
     * trwającej sesji STOMP nie generuje żadnego ERROR (principal jest ustawiany raz, przy CONNECT).
     */
    WS_TOKEN_EXPIRED,
    /** Token JWT nieprawidłowy (podpis, issuer, claims, format). */
    WS_TOKEN_INVALID,
    /** Każdy inny/nieoczekiwany błąd przetwarzania ramki; bez szczegółów wewnętrznych w ramce. */
    WS_INTERNAL;

    /** Nazwa natywnego nagłówka STOMP z kodem błędu. */
    public static final String HEADER = "code";
}
