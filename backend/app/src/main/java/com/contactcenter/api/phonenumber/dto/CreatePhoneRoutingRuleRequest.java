package com.contactcenter.api.phonenumber.dto;

import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO żądania tworzenia nowej reguły routingu dla numeru telefonu.
 *
 * <p>Walidacja cross-field:
 * <ul>
 *   <li>{@code timeEnd > timeStart} – metoda {@link #isTimeRangeValid()}</li>
 *   <li>Dokładnie jeden z {@code ivrTreeId} / {@code queueId} musi być != null –
 *       metoda {@link #isExactlyOneTargetSet()}</li>
 * </ul>
 */
public record CreatePhoneRoutingRuleRequest(

        /**
         * Dni tygodnia (ISO 8601: 1=Pon, 7=Nie). Co najmniej jeden element.
         * Każdy element musi być z zakresu 1–7.
         */
        @NotNull(message = "Lista dni tygodnia jest wymagana")
        @Size(min = 1, message = "Wymagany co najmniej jeden dzień tygodnia")
        List<@Min(value = 1, message = "Dzień tygodnia musi być >= 1") @Max(value = 7, message = "Dzień tygodnia musi być <= 7") Integer> daysOfWeek,

        /** Czas rozpoczęcia okna obsługi. Wymagany. */
        @NotNull(message = "Czas rozpoczęcia jest wymagany")
        LocalTime timeStart,

        /** Czas zakończenia okna obsługi. Wymagany. Musi być późniejszy niż timeStart. */
        @NotNull(message = "Czas zakończenia jest wymagany")
        LocalTime timeEnd,

        /** UUID drzewa IVR. Dokładnie jedno z: ivrTreeId / queueId musi być podane. */
        UUID ivrTreeId,

        /** UUID kolejki. Dokładnie jedno z: ivrTreeId / queueId musi być podane. */
        UUID queueId,

        /** Czy reguła jest aktywna. Domyślnie {@code true}. */
        Boolean isActive
) {

    /**
     * Sprawdza czy timeEnd jest późniejszy niż timeStart.
     *
     * <p>Metoda pomocnicza używana w serwisie do walidacji cross-field
     * (Bean Validation nie może tego sprawdzić na poziomie pola).
     */
    public boolean isTimeRangeValid() {
        if (timeStart == null || timeEnd == null) {
            return true; // brak pól obsłuży @NotNull
        }
        return timeEnd.isAfter(timeStart);
    }

    /**
     * Sprawdza czy dokładnie jeden cel routingu jest podany (ivrTreeId XOR queueId).
     */
    public boolean isExactlyOneTargetSet() {
        return (ivrTreeId != null) ^ (queueId != null);
    }
}
