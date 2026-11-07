---
name: tura73-ghost-agents
description: Tura 73 (2026-11-07) — "agenci-duchy" (BE-210/BE-211/FE-164) + BE-212 (fałszywy lag ETL); liczniki 391/83/7=481, następne BE-213/FE-165/DB-098
metadata:
  type: project
---

Tura 73 (tylko dokumentacja): BE-210 (wykrywanie obecności + auto OFFLINE, wysoki, L), BE-211 (log pollu routingu na DEBUG, XS), FE-164 (heartbeat/komunikat po OFFLINE, zależy od BE-210). Przypięte do EPIC-32 (backlog; ta sama rodzina co BE-165/166/204). Liczniki: DB 87/7/3=97, BE 159/53/3=215, FE 145/22/1=168, RAZEM 391/82/7=480. Następne wolne: BE-212, FE-165, DB-098.

**Why:** OFFLINE tylko przy jawnym wylogowaniu; brak SessionDisconnectEvent/heartbeatu; FE nie wysyła /app/ping (heartbeatIncoming 0); komentarz RedisConfig o TTL 30 s niezgodny z kodem (8 h).
**How to apply:** pytanie otwarte do właściciela — BUSY (rekomendacja: nie przełączać przy aktywnym kontakcie PHONE; osierocony BUSY ~10 min) i BREAK (ta sama karencja). Rejestr sesji musi być w Redis (EPIC-33: BE-172, BE-174/175). Ewentualny DB-098 tylko jeśli kolumna ostatniej aktywności. Skrypt epików: EPIC-32 w TASKS-BACKEND ma 3 miejsca liczników (tabela l.59, "Ukończone 13/31", nota l.~8480) + PROGRESS wiersz EPIC-32.

**Dopisek BE-212 (2026-11-07):** [BUG] fałszywy alarm lagu ETL (`checkLagAndAlert` = now - last_synced_at, znacznik CDC), Średni/S, powiązany z BE-211, BE-030/BE-030b (ETL campaign_contact, V045 — BE-030b nie oddzielny ticket DB), BE-141, BE-150, BE-172. ETL/DW należy do EPIC-10 (zamknięty 5/5) — wpisany do backlogu EPIC-32 (tabela 16/0/19/0/35, nagłówek 13/32, PROGRESS 16/35 i 33/51, lista „BE 19 ⬜"), w nagłówku `**Epic:**` jawnie EPIC-10. Liczniki: DB 87/7/3=97, BE 159/54/3=216, FE 145/22/1=168, RAZEM 391/83/7=481. Następne wolne: BE-213, FE-165, DB-098.
**Why:** zamknięty epik nie jest otwierany; backlog liczony w epiku otwartym (precedens BE-210/BE-211).
**How to apply:** TASKS-BACKEND tabela RAZEM (l.~66) była już stale (207 vs suma wierszy 213 vs PROGRESS 215) — dodawano tylko +1; poprawić przy rekoncyliacji. Skrypt ⬜ na `### BE-`: 50 + 4 BE-T = 54 zgodne.

**Dopisek 2 (2026-11-07, gałąź fix/routing-conditional-assignment-and-limits):** BE-211 ✅ i BE-212 ✅ (CR 3,5/5 APPROVE WITH FIXES, 0 must-fix; mvn verify 3089). Nowe ⬜: BE-213 ([BUG] P1 utrata danych ETL: fetch CDC LIMIT 5000 vs computeMax* bez limitu + wyścig; Epic: EPIC-10, liczony w backlogu EPIC-32), BE-214 (cooldown alertów ETL + nity z CR), DB-098 (indeksy wyrażeniowe COALESCE(updated_at, created_at), bramkowany wolumenem), FE-165 (panel admin-metrics: status ERROR/zastały lastRunAt, Could Have S). BE-172 dopisano checkJobLiveness/joby ETL bez ShedLock + martwy @Transactional w readAndLockSyncState. Liczniki: DB 87/8/3=98, BE 161/54/3=218, FE 145/23/1=169, RAZEM 393/85/7=485. Następne wolne: BE-215, FE-166, DB-099 (DB-098 wcześniej "zarezerwowany" w BE-208/BE-210 na migrację historii/ostatniej aktywności — przepisane na DB-099).
**Why:** tabele "Indeks wg EPIC" były stale: TASKS-BACKEND RAZEM 153/52/208 (faktycznie 159/54/216 przed turą), nagłówek EPIC-32 "13/32" (faktycznie 16/35), TASKS-FRONTEND EPIC-32 49 (faktycznie 50) — naprawione w tej turze; po turze: BE EPIC-32 18/19/37, RAZEM 161/54/3=218, FE EPIC-32 33/18/51 Razem 169.
**How to apply:** skrypty liczące (po nagłówkach ### + status) trzymać w scratchpadzie i uruchamiać na początku tury; sprawdzać też per-sekcja (## EPIC-xx) wobec tabeli epików. Przy zajmowaniu numeru grepować "następny wolny DB-0xx" w ticketach (rezerwacje w tekście ryzyk).
