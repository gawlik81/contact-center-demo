---
name: MockCallController + TwilioTelephonyAdapter — niespójna konfiguracja warunkowa
description: @ConditionalOnProperty(name="telephony.provider", havingValue="mock", matchIfMissing=true) NIE wyklucza MockCallController gdy twilio.enabled=true a telephony.provider jest nieustawione (domyślnie mock). Skutek: dwa adaptery aktywne jednocześnie, sesje w różnych mapach ConcurrentHashMap.
type: project
---

`MockCallController` ma warunek `@ConditionalOnProperty(name = "telephony.provider", havingValue = "mock", matchIfMissing = true)`. Gdy `telephony.provider` nie jest ustawione w konfiguracji (co jest typowe dla dev), `matchIfMissing = true` powoduje że bean jest zawsze rejestrowany — nawet przy `twilio.enabled=true`.

**Why:** Odkryto w sesjach 2026-03-27 07:10–07:13 i 07:50–07:51. Agent wysyła action=ANSWER do `/api/dev/telephony/simulate`, `MockCallController` deleguje do `MockTelephonyAdapter` (wstrzykniętego bezpośrednio, nie przez interfejs), który nie ma sesji Twilio zarejestrowanych w `TwilioTelephonyAdapter.sessions`. Rzuca `TelephonyException: Sesja nie istnieje`.

**How to apply:** Przy wykluczaniu beana dev gdy provider produkcyjny jest aktywny, użyj dwóch warunków lub `@ConditionalOnExpression`. Poprawny warunek: `@ConditionalOnExpression("'${telephony.provider:mock}' == 'mock' && !${twilio.enabled:false}")`. Nigdy nie polegaj tylko na `matchIfMissing=true` gdy inne beany mają własne włączniki (np. `twilio.enabled`).
