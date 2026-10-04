---
name: BE-134 email_message na kluczu złożonym (message_id, message_at)
description: Stan po BE-134 (2026-10-04): EmailMessage @IdClass, natywny INSERT/UPDATE, lookupy po samym id, EmailEvent.messageAt, V103 odłożona, WP-4 open
metadata:
  type: project
---

Encja `EmailMessage` ma `@IdClass(EmailMessageId)` `(id, messageAt)` — wzorzec 1:1 z `SocialMessage` (BE-132) i `ContactEvent` (BE-117). Tabela partycjonowana RANGE(message_at) od V102 (DB-067).

**Kluczowe decyzje (stan na 2026-10-04):**
- `save` = natywny INSERT z `WHERE NOT EXISTS` po (tenant, message_id_header) pod `pg_advisory_xact_lock`; zwraca `Optional` (duplikat = empty). `update` = natywny UPDATE po pełnym kluczu, 0 wierszy → wyjątek.
- `findById(id)` = lookup po samym id (koszt: PK każdej partycji) — tylko API/fallback; `findById(id, messageAt)` = `em.find` po pełnym kluczu.
- `deleteByIds` = `DELETE … USING jsonb_to_recordset(:rows)`, zwraca potwierdzone pełne klucze.
- Purge: `AttachmentsRow` niesie `messageAt`; sieroty sortowane i filtrowane po `message_at` (indeks częściowy V102).
- `EmailEvent` ma `messageAt` na końcu rekordu; brak pola w starych zdarzeniach = `null` → fallback lookup po id.
- INBOUND `messageAt` = INTERNALDATE (`getReceivedDate()`), nie nagłówek `Date`.
- V103 (`DROP DEFAULT` na `message_at`) NIE dodana: fixture'y testowe piszą surowy SQL bez `message_at`. Do decyzji osobno.
- Wdrożenie razem z DB-067, WP-4 (local-demo) i decyzja o oknie blokady — po stronie właściciela.

**Why:** DB-067 wymagał klucza partycji; BE-134 domyka kod. Stan testów: `mvn -o clean verify -pl app` 2307/0/0.

**How to apply:** nowe zapytania po `email_message` podawaj `message_at`, gdy jest znany (pruning). Nie używaj `em.merge`/`em.find` po samym `message_id`. Zdarzenia RabbitMQ niosą `messageAt` — przy nowych konsumentach przekazuj pełny klucz.

Powiązane: [[feedback_native_insert_on_conflict_deferrable_unique]], [[feedback_explain_empty_partitions_seq_scan]], [[feedback_timestamptz_microsecond_keys]], [[feedback_partitioned_table_jpa]].
