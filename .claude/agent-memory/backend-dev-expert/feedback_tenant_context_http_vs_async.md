---
name: TenantContext clear() – tylko w ścieżce async, nie HTTP
description: Metody serwisowe wywoływane z HTTP nie powinny czyścić TenantContext w finally
type: feedback
---

Metody serwisowe wywoływane bezpośrednio z kontrolerów HTTP (nie przez RabbitMQ) NIE powinny ustawiać ani czyścić TenantContext.

- HTTP path: TenantFilter zarządza cyklem życia kontekstu (ustawia na początku żądania, czyści w finally). Serwis wywoływany z kontrolera może polegać na istniejącym kontekście.
- Async path (RabbitMQ listener): brak TenantFilter – serwis MUSI ręcznie ustawić TenantContext z danych eventu i wyczyścić go w finally.

**Why:** `DialerCallbackHandler.handleCallbackDisposition` była wywoływana z HTTP kontrolera, ale miała `TenantContext.clear()` w finally – niszczyła kontekst potrzebny do dalszych operacji w tym samym żądaniu.

**How to apply:** Przed dodaniem set/clear TenantContext w metodzie serwisowej sprawdź jak jest wywoływana. Jeśli zarówno z HTTP jak i async – rozdziel wzorzec lub przekazuj tenantId jako parametr (jak po poprawce BE-024).
