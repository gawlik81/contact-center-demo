---
name: MockCallController nieobecny gdy telephony.provider=twilio — frontend dostaje 500
description: application.yml z telephony.provider=twilio powoduje że MockCallController nie jest tworzony; żądania /api/dev/telephony/simulate zwracają NoResourceFoundException (HTTP 500 zamiast 404)
type: feedback
---

Po wdrożeniu naprawy `@ConditionalOnProperty` na `MockCallController` i zmianie
`telephony.provider: ${TELEPHONY_PROVIDER:twilio}` w `application.yml`, bean `MockCallController`
nie jest rejestrowany gdy `telephony.provider=twilio`.

Spring `DispatcherServlet` przekierowuje żądania `POST /api/dev/telephony/simulate` do
`ResourceHttpRequestHandler` (statyczne zasoby) który rzuca `NoResourceFoundException`.
`GlobalExceptionHandler` mapuje to jako HTTP 500 (nie 404).

**Why:** Brak kontrolera dla ścieżki to 404, ale `GlobalExceptionHandler` traktuje
`NoResourceFoundException` jako nieoczekiwany błąd 500. Dodatkowo frontend nadal
używa starego endpointu niezależnie od aktywnego adaptera.

**How to apply:** Gdy widzisz `NoResourceFoundException: No static resource api/dev/telephony/simulate`
— to nie jest błąd infrastruktury, tylko brak beana. Sprawdź `telephony.provider` w konfiguracji.
Naprawa wymaga zmiany po stronie frontendu (inny endpoint dla Twilio) lub
dodania właściwego endpointu odbioru dla Twilio w backendzie.
