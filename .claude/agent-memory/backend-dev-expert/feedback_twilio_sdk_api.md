---
name: Twilio SDK 10.x API differences
description: Twilio SDK 10.1.5 API differs significantly from older versions – key class/enum name changes
type: feedback
---

W Twilio SDK 10.1.5 (użyty w projekcie) zmiany względem starszych wersji:

- `Call.Creator` (nested class) NIE ISTNIEJE – `Call.creator()` zwraca `CallCreator` (oddzielna klasa `com.twilio.rest.api.v2010.account.CallCreator`)
- `CallUpdater.Status` (enum) NIE ISTNIEJE – prawidłowy enum to `Call.UpdateStatus` z wartościami `CANCELED` i `COMPLETED`
- `CallUpdater.setStatus()` przyjmuje `Call.UpdateStatus`, nie `CallUpdater.Status`
- `CallCreator.create()` i `CallUpdater.update()` bez parametrów są dostępne przez klasę bazową `Creator`/`Updater`
- Metoda `setStatusCallback` ma dwa overloady: `(URI)` i `(String)` – w Mockito `any()` jest niejednoznaczne; używaj `any(URI.class)` lub `anyString()`
- Metoda `setStatusCallbackEvent` ma dwa overloady: `(List<String>)` i `(String)` – używaj `anyList()` w mockach

**Why:** Napotkane podczas implementacji TwilioTelephonyAdapter, błędy kompilacji na `Call.Creator` i `CallUpdater.Status`.

**How to apply:** Gdy piszesz lub przeglądasz kod Twilio SDK w tym projekcie, zawsze sprawdzaj API przez `javap` lub dokumentację 10.x, a nie zakładaj kompatybilności z wcześniejszymi wersjami.
