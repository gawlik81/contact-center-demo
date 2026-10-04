---
name: project_db067_email_message_partitioning
description: DB-067 (2026-10-04) — partycjonowanie email_message RANGE(message_at): V101 (kolumna+backfill+DEFAULT now()) + V102 (online swap, dynamiczne partycje, RLS, trigger niemodyfikowalności, v_customer_timeline DROP/CREATE); wyniki scratch 500k, 8 istniejących testów Java do zmiany w BE-134; partycje dziedzicza GRANT app_user (obejście RLS)
metadata:
  type: project
---

**DB-067 zrealizowane w DB-level (2026-10-04), BE-134 (Java) jeszcze nie.** Pliki: `V101__email_message_add_message_at.sql`, `V102__partition_email_message.sql`, test `domain/email/EmailMessagePartitioningTest` (27/27 zielone, własny kontener, pełny łańcuch V001..V102 + pre/post backfill).

**Decyzje:** D2 zamknięta przez właściciela (partycjonujemy przed produkcją). D4 = A to założenie robocze — NIE potwierdzone formalnie przez PO. Numery V101/V102 wolne na wszystkich gałęziach (najwyższa na develop = V100).

**Co zmieniono vs ticket (świadome odstępstwa):**
- Brak triggera BEFORE INSERT jako „sieci bezpieczeństwa" — trigger BEFORE ROW na tabeli partycjonowanej odpala się PO routingu, przy NULL w kluczu = błąd "moving row to another partition". Siecią jest `DEFAULT now()` na `message_at` (ustawiany PO backfillu w V101). Patrz [[feedback_partitioned_table_before_trigger_routing]].
- DEFAULT now() jest PRZEJŚCIOWY (do DROP DEFAULT po wdrożeniu BE-134), bo cicho daje now() zamiast INTERNALDATE.
- Rollback „RENAME z powrotem przed DROP _old" niemożliwy w tej samej transakcji: wycofanie po COMMIT = backup albo reczna procedura odwrotna. Gdyby był potrzebny okres obserwacji, DROP trzeba przenieść do osobnej migracji (decyzja właściciela).
- Indeks sierot odtworzony jako `idx_email_message_tenant_orphan_age` na `(tenant_id, message_at) WHERE contact_id IS NULL` (nazwa zachowana). Dodany też `idx_email_message_tenant_message_at (tenant_id, message_at)` pod purge.

**Wyniki scratch (PG 16.13, 500 tys. wierszy, 3 tenanty, 49 850 sierot, 15 partycji miesięcznych 2025-10..2026-12 + default):**
- Backfill 500 000 wierszy: ~18 s (V101); swap V102: ~35 s (ACCESS EXCLUSIVE przez cały czas).
- Odcisk zawartości przed == po (md5 po message_id/header/created/attachments): identyczny.
- EXPLAIN ANALYZE (średnio, ms) przed → po: contact-page 0,2 → 0,9; contact-count 0,2 → 0,7; first-inbound 0,1 → 0,7; header-lookup 0,1 → 0,6; thread (OR in_reply_to, bez indeksu) ~100 → ~60–80; findAll (sort po created_at, bez indeksu) ~110 → ~160–175; sweep sierot wg COALESCE 15 → 20–40 (indeks częściowy NIE pasuje do wyrażenia — BE-134 MUSI przejść na message_at). Pruning po `message_at < X`: skanuje tylko partycje < X + default (zweryfikowane).

**Pułapki odkryte (zapisane też w feedback):** [[feedback_partitioned_table_before_trigger_routing]], [[feedback_seed_planner_and_catalog_pitfalls_2026_10]].

**REVOKE na partycjach (decyzja właściciela 2026-10-04) — ROZWIĄZANE:** V102 dodaje REVOKE ALL FROM app_user (pętla po pg_inherits po swapie, w tym _default; w `create_email_message_partition` na nowej partycji; asercja w sekcji 12). Weryfikacja na świeżym kontenerze scratch: przed REVOKE pod GUC=T2 partycja zwraca 3 wiersze (2 cudze), przez rodzica 1; po REVOKE przez rodzica SELECT/INSERT (routing + _default)/UPDATE/DELETE działają, bezpośrednio = 42501. Testy: `EmailMessagePartitioningTest$DirectPartitionAccess` (4) i `$ViaParentAfterRevoke` (2); RED bez REVOKE: 4/4 bezpośrednich pada. Social: V103 (ta sama logika, osobna migracja, V100 zastosowana w demo). Szczegóły: [[feedback_partition_grants_revoke]].

**Numeracja:** V103 = REVOKE social_message (NIE `DROP DEFAULT` na message_at — ta migracja dostanie pierwszy wolny numer po V103). Wymóg wdrożeniowy: `pg_dump -Fc` przed pierwszym wdrożeniem produkcyjnym (V102 robi DROP email_message_old w tej samej transakcji co swap).

**Ryzyka otwarte:**
- Partycje tabel tenantowych `contact`, `contact_event`, `contact_transcription`, `contact_ai_summary`, `audit_log`, `plugin_invocation_log` mają ten sam GRANT-owy problem (poza zakresem DB-067; zgłoszone właścicielowi jako lista). DB-073 obejmuje tylko campaign_contact.
- Partycja DEFAULT blokuje tworzenie partycji dla miesiąca, w którym leżą wiersze w default (ALTER/CREATE PARTITION OF weryfikuje constraint). Monitorować `_default` (WP-5).
- Istniejące testy Java po zmianie schematu (do poprawy w BE-134, NIE naprawiane w DB-067): EmailMessagePurgeIntegrationTest$QueryPlans.explain_usesContactIndexAndPrimaryKey (literalna nazwa indeksu), EmailMessageOrphanPurgeIntegrationTest$QueryPlans (2× literalna nazwa + COALESCE), OrphanMessagePurgeIndexesTest (definicja + komentarz + plan), SocialMessagePartitioningTest$PartitionFunctions (7 → 8 tabel w create_next_month_partitions).

**How to apply:** przed BE-134 nie zakładać, że `EXPLAIN` pokaże literalne nazwy indeksów (użyj `PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren`). Przy kolejnych ticketach partycjonowania tej rodziny (campaign_contact, DB-073) uwzględnić REVOKE na partycjach. Powiązane: [[project_db065_social_message_partitioning]], [[project_db066_email_volume_gate]], [[feedback_rls_testing]].
