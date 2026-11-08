---
name: tura74-etl-presence
description: Tura 74 (2026-11-08) — BE-213/BE-210/FE-164 zamknięte, nowe BE-215/216/217 i FE-166; liczniki 396/86/7=489, następne BE-218/FE-167/DB-099
metadata:
  type: project
---

Tura 74 (gałąź `fix/etl-watermark-and-agent-presence` z develop aa2cdb81, po PR #61; niezacommitowane). ✅ BE-213 (ETL CDC `runCdcLoop`, kursor keyset, okno bezpieczeństwa 60 s), ✅ BE-210 (obecność agenta, pakiet `domain/presence`, decyzje właściciela: 90 s / BUSY 10 min bez kontaktu PHONE / brak sendBeacon), ✅ FE-164. Nowe ⬜: BE-215 ([BUG] MV ClickHouse zawyża agregaty; EPIC-10/backlog EPIC-32), BE-216 (długie transakcje vs okno bezpieczeństwa), BE-217 (follow-up presence), FE-166 (decyzja reload/login, test karty w tle). DB-098 podniesiony do Should Have. Liczniki: DB 87/0/8/3=98, BE 163/0/55/3=221, FE 146/0/23/1=170, RAZEM 396/86/7=489. Następne wolne: BE-218, FE-167, DB-099.

**Why:** BE-213 czyta ponownie ogon remisów, więc MV SummingMergeTree w ClickHouse dolicza duplikaty; `PostgresDwWriter.upsertCampaigns` to CELOWY stub (ClickHouseDwWriter ma implementację) — nie wymaga ticketu.
**How to apply:** skrypt po nagłówkach `###` daje ✅ BE o 2 mniej niż PROGRESS (BE-108/109 bez nagłówka) i liczy `[x]` (BE-067) osobno — porównywać delty, nie wartości bezwzględne (po turze: skrypt BE 161✅+[x]… = 159+2 → 161, ⬜ 51). Punkty „weryfikacja na local-demo” (zamknięcie karty → OFFLINE 90–120 s, brak WARN lagu) są otwartymi kryteriami BE-210/212/213, nie osobnym ticketem. Edycje robić skryptem Pythona z asercjami (pliki TASKS mają 5–11 tys. linii); skrypt nie jest idempotentny — przy asercji w połowie sprawdzić, które pliki już zapisano.
