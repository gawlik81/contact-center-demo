---
name: BE-036 Contact API Advanced Filters
description: Rozszerzenie GET /api/contacts o 5 filtrów zaawansowanych — wzorzec appendFilterConditions i ContactFilterParams record
type: project
---

Rozszerzono `GET /api/contacts` (BE-027) o dodatkowe filtry dla widoku „Raporty > Kontakty" (FE-029).

**Why:** Widok raportów potrzebuje filtrowania po kolejce, kampanii, numerze telefonu i czasie trwania. Indeksy DB-022 (V035) umożliwiają wydajne zapytania.

**How to apply:** Przy kolejnych rozszerzeniach ContactFilterParams — dodaj pola do record, rozszerz `appendFilterConditions`, zaktualizuj sygnatury `findContacts`/`countContacts`.

## Wzorzec appendFilterConditions

`ContactRepository.appendFilterConditions()` buduje dynamiczne WHERE przez StringBuilder + Map<String,Object>. Null-check po stronie Java (nie `:param IS NULL` w SQL — Hibernate 6 nie obsługuje tego poprawnie z UUID/String). Przekazuj null gdy filtr nieaktywny.

## Nowe filtry (zrealizowane 2026-04-08)

| Parametr | SQL | Uwagi |
|---|---|---|
| `queueId` (String) | `queue_id = CAST(:queueId AS uuid)` | UUID jako String w record |
| `campaignId` (String) | `campaign_id = CAST(:campaignId AS uuid)` | UUID jako String w record |
| `remoteAddress` (String) | `remote_address ILIKE '%' \|\| :remoteAddress \|\| '%'` | partial match |
| `durationMin` (Integer) | `duration_seconds IS NOT NULL AND duration_seconds >= :durationMin` | IS NOT NULL guard obowiązkowy |
| `durationMax` (Integer) | `duration_seconds IS NOT NULL AND duration_seconds <= :durationMax` | IS NOT NULL guard obowiązkowy |

## Walidacja w ContactFilterParams (record)

```java
@Size(max = 36) String queueId,
@Size(max = 36) String campaignId,
String remoteAddress,
@Min(0) Integer durationMin,
@Min(0) Integer durationMax,
```

Analogiczne adnotacje `@Size`/`@Min` w `ContactController` na `@RequestParam`.

## Pliki zmienione

- `ContactFilterParams.java` — 5 nowych pól (record, 13 parametrów łącznie)
- `ContactRepository.java` — `appendFilterConditions` rozszerzona sygnatura, `findContacts`/`countContacts` zaktualizowane
- `ContactService.java` — `listContacts` przekazuje nowe params z ContactFilterParams
- `ContactController.java` — 5 nowych `@RequestParam` z walidacją Bean Validation
- `ContactServiceTest.java` — 3 nowe testy w `ListContactsTests` (filterByQueueId, filterByDurationMin, kombinacja AND)
