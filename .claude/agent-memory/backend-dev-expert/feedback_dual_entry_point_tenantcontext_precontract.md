---
name: feedback_dual_entry_point_tenantcontext_precontract
description: Gdy logika @Scheduled joba per-tenant zyskuje drugi entry point (REST, "uruchom teraz" dla jednego tenanta), rdzeń per-tenant NIE MOŻE zarządzać TenantContext — zarządzanie zostaje wyłącznie w pętli schedulera
type: feedback
---

Gdy istniejący `@Scheduled` job z pętlą per-tenant (patrz [[feedback_scheduled_job_tenantcontext_missing]] — dlaczego w ogóle musi ustawiać `TenantContext`) dostaje DRUGI entry point wywoływany z REST dla JEDNEGO tenanta (np. przycisk admina "przelicz teraz" zamiast czekania na cron), refaktor musi rozdzielić dwie role, które wcześniej żyły w jednej metodzie:

1. **Rdzeń per-tenant** (zapis wyniku, ewentualny efekt uboczny typu auto-purge) — NIE dotyka `TenantContext` w ogóle. Jawny prekontrakt w Javadoc: "TenantContext musi być już ustawiony przez wywołującego".
2. **Pętla schedulera** (`runForAllActiveTenants`/odpowiednik) — jedyne miejsce, które woła `TenantContext.setTenantId(tenantId)` na początku iteracji i `TenantContext.clear()` w `finally` obejmującym całą iterację (wątek puli reużywany między tenantami).
3. **Metoda REST** (`runForTenant(tenantId)`/odpowiednik) — woła rdzeń BEZPOŚREDNIO, bez własnego `set`/`clear`. Kontekst jest już poprawny z wątku HTTP (`TenantFilter` + weryfikacja "własny tenant" w kontrolerze PRZED wywołaniem serwisu).

**Why:** `TenantContext.clear()` wywołane w trakcie obsługi żądania HTTP wyczyściłoby kontekst dla RESZTY łańcucha przetwarzania TEGO SAMEGO żądania (logowanie, audyt dalej w łańcuchu) — to jest DOKŁADNIE ten sam bug co brak `setTenantId` w wątku schedulera (patrz [[feedback_scheduled_job_tenantcontext_missing]]), tylko w przeciwnym kierunku i dużo trudniejszy do wykrycia testem jednostkowym z mockami (mock nie przechodzi przez prawdziwy `assertSameTenant`, więc nie zauważy nadpisania/wyczyszczenia kontekstu). Odkryte i naprawione prewencyjnie przy BE-112/BE-118 (EPIC-29, `RetentionEvaluationService.runForTenant`, 2026-08-13) — literalne przeniesienie starej metody `persistAndMaybeAutoPurge` (z wbudowanym `set`/`finally clear`) do wywołania z REST wprowadziłoby ten bug.

**How to apply:**
- Przy dodawaniu "ręcznego triggera" dla istniejącego `@Scheduled` joba z pętlą per-tenant: NIE wołaj wprost istniejącej metody pętli z nowym argumentem `List.of(jedenTenant)` — ona zarządza kontekstem i go czyści.
- Wydziel rdzeń bez `set`/`clear`, udokumentuj prekontrakt w Javadoc, i dopiero wokół niego zbuduj dwie cienkie ścieżki (pętla z `set`/`clear` dla schedulera, bezpośrednie wywołanie dla REST).
- Test regresyjny dla ścieżki REST: ustaw `TenantContext.setTenantId(x)` PRZED wywołaniem metody serwisu (symulacja wątku HTTP), wywołaj metodę, zweryfikuj `TenantContext.getTenantIdOrNull()` nadal równe `x` PO wywołaniu — nawet gdy jedna z wewnętrznych kategorii/kroków rzuca wyjątek (regresja "clear() w finally, które nie powinno tam być").
- Jeśli ręczna ścieżka ma dodatkowe ograniczenie bezpieczeństwa (np. "bez auto-purge", "bez efektów ubocznych usuwających dane") — przewlecz jawny `boolean` parametr przez rdzeń zamiast dwóch prawie identycznych kopii metody.
