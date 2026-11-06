package com.contactcenter.domain.exception;

/**
 * Wyjątek walidacji biznesowej – żądanie jest poprawne składniowo, ale narusza regułę domenową.
 *
 * <p>Mapowany na HTTP 422 {@code problem+json} (type {@code invalid-argument}) przez
 * {@code GlobalExceptionHandler}. Komunikat trafia do klienta w polu {@code detail} (FE wyświetla go
 * użytkownikowi), dlatego MUSI być świadomie napisanym, bezpiecznym tekstem: bez nazw klas, pól
 * encji, zapytań, stack trace'ów ani innych szczegółów wewnętrznych.
 *
 * <p><strong>Różnica od {@link IllegalArgumentException}:</strong> {@code IllegalArgumentException}
 * rzucają także biblioteki i kod techniczny (np. {@code Enum.valueOf}, {@code UUID.fromString},
 * Hibernate) – jest traktowany jako błąd programistyczny i mapowany na HTTP 500 bez ujawniania
 * komunikatu (BE-200). Walidację biznesową dostępną przez REST zgłaszaj tym wyjątkiem.
 */
public class BusinessValidationException extends RuntimeException {

    public BusinessValidationException(String message) {
        super(message);
    }

    public BusinessValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
