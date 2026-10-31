---
name: project-db084-wp1-tura36
description: WP-1 DB-084 wykonane (tura 36, 2026-10-11) - model tabela tenant_campaign_archive_setting, campaign.ended_at, DB-084 przed DB-083, STOPPED terminalne
metadata:
  type: project
---

Decyzje właściciela 2026-10-11 (źródło: DESIGN-db084-wp1-model-danych.md): osobna tabela `tenant_campaign_archive_setting` (brak wiersza = 180, bez backfillu wierszy), nowe pole `campaign.ended_at` z triggerem (backfill z DISABLE TRIGGER trg_campaign_updated_at), podgląd skutku w BE-151/FE-113, DB-084 PRZED DB-083 (DB-083 `Zależy od` DB-084; ALTER FUNCTION zamiast CREATE OR REPLACE).

**Why:** CREATE OR REPLACE zeruje SECURITY DEFINER/search_path; naiwny UPDATE campaign resetuje updated_at; STOPPED/COMPLETED są terminalne w kodze (zdanie "STOPPED może być wznawiana" było fałszywe).

**How to apply:** liczniki po turze 36 bez zmian (330/348, ⬜ 11); skrypt count.py w scratchpadzie widzi BE 149/139 (BE-108/109 bez nagłówka ###, dodaj +2). Poprawka testów CampaignArchiveJobIntegrationTest/Db082... należy do DB-084. Zob. [[project-progress-state]].
