package com.contactcenter.api.phonenumber.dto;

import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO żądania częściowej aktualizacji reguły routingu (PATCH semantics).
 *
 * <p>Wszystkie pola są opcjonalne – {@code null} oznacza brak zmiany danego pola.
 * Gdy podane są {@code timeStart} / {@code timeEnd}, serwis zbuduje aktualny stan
 * (merge z istniejącymi wartościami) i wykona walidację cross-field.
 */
public record UpdatePhoneRoutingRuleRequest(

        /**
         * Nowe dni tygodnia (ISO 8601: 1=Pon, 7=Nie). {@code null} = brak zmiany.
         * Gdy podane – co najmniej jeden element, każdy z zakresu 1–7.
         */
        @Size(min = 1, message = "Wymagany co najmniej jeden dzień tygodnia")
        List<@Min(value = 1, message = "Dzień tygodnia musi być >= 1") @Max(value = 7, message = "Dzień tygodnia musi być <= 7") Integer> daysOfWeek,

        /** Nowy czas rozpoczęcia okna obsługi. {@code null} = brak zmiany. */
        LocalTime timeStart,

        /** Nowy czas zakończenia okna obsługi. {@code null} = brak zmiany. */
        LocalTime timeEnd,

        /** Nowy UUID drzewa IVR. {@code null} = brak zmiany (chyba że podano queueId). */
        UUID ivrTreeId,

        /** Nowy UUID kolejki. {@code null} = brak zmiany (chyba że podano ivrTreeId). */
        UUID queueId,

        /** Nowy stan aktywności reguły. {@code null} = brak zmiany. */
        Boolean isActive
) {
}
