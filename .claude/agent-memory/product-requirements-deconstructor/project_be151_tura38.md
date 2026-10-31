---
name: project-be151-tura38
description: Tura 38 (2026-10-11) — BE-151 zakończone (commit 760fb263), liczniki 332/348, FE-113 odblokowany, luka globalnego handlera 500 jako rekomendacja bez numeru
metadata:
  type: project
---

BE-151 ✅ (API `retention/campaign-archive` GET/PUT + preview, V139, audyt best-effort nieudowodniony w audit_log, CR 4/5, mvn verify 2627/0/0/1). Liczniki: DB 79/84, BE 142/151, FE 111/113, RAZEM 332/348, ⬜ 9 (DB-069, DB-083, BE-137, BE-139, BE-144, BE-148, BE-149, BE-150, FE-113), N/A 7; EPIC-30 ⬜ 8 z 61. Następnie FE-113, potem WP-4 BE-120.

**Why:** luka `GlobalExceptionHandler` (500 dla nieczytelnego JSON) NIE została założona jako BE-152 — bez zgody właściciela nowe tickety nie wchodzą do liczników (wzorzec tury 33: ticket po "tak, dodaj").

**How to apply:** jeśli właściciel zatwierdzi, następny wolny numer BE-152 (mianownik 349, ⬜ 10). count.py widzi BE 140 ✅ ze statusów TASKS (+2 za BE-108/109). V136–V139 niezastosowane na żywej bazie (max 135). Zob. [[project-progress-state]].
