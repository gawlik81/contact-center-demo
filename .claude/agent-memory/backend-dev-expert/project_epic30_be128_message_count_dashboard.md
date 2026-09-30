---
name: project_epic30_be128_message_count_dashboard
description: EPIC-30 BE-128 — RetentionEvaluationServiceImpl dolicza wiadomości (osierocone+powiązane) do eligibleRowCount CONTACT_INTERACTIONS; decyzja DOKŁADNE nie oszacowanie, dowód EXPLAIN
metadata:
  type: project
---

BE-128 (2026-09-26) — `eligibleRowCount` kategorii `CONTACT_INTERACTIONS` (dashboard FE-105/badge
FE-108, `RetentionSummaryDto`) dolicza teraz wiadomości do liczby `contact`+`contact_event` liczonej
partycyjnie przez `PartitionScanner`. Cztery składniki, wszystkie DOKŁADNE (nie oszacowanie):
`EmailMessageService#countOrphansOlderThan`/`SocialMessageService#countOrphansOlderThan` (reużycie
BE-127) + NOWE `EmailMessageService#countLinkedToContactsOlderThan`/
`SocialMessageService#countLinkedToContactsOlderThan` (JOIN/IN-subquery do `contact`, ten sam cutoff).

**Decyzja EXACT vs estimate — zweryfikowana EMPIRYCZNIE, nie zgadnięta.** Ticket dopuszczał
oszacowanie/pominięcie przy kosztownym dokładnym liczeniu. Metodologia „EXPLAIN na scratch" (wzorzec
z BE-127): baza `scratch_be128` w `cc-postgres`, 100 tys. kontaktów + 10 tenantów (1,16 mln wierszy
`email_message`/`social_message` razem, tenant docelowy ~10% udziału — symulacja realistycznej
wielotenantowej selektywności, USUNIĘTA po pracy). Wynik: przy 100% udziału tenanta planner robi
`Seq Scan` całej tabeli (potwierdza naiwną obawę); przy ~10% udziału planner PRZESTAWIA SIĘ na
`Bitmap Index Scan` na `uq_email_message_id_header`/`idx_social_message_sender` — OBA istniejące
indeksy z V010 (`tenant_id` jako pierwsza kolumna unikalnego/złożonego indeksu), ŻADNA nowa migracja
nie była potrzebna. Koszt jest więc ograniczony do wierszy TEGO tenanta, nie całej platformy — patrz
[[feedback_tenant_prefixed_unique_index_reuse]] po generalizację tego wzorca.

**Dwa punkty wejścia (WP-2):** nowa `countEligibleMessages(tenantId)` (private, bez `set`/`clear`
kontekstu) wołana WYŁĄCZNIE z `persistSummaryAndMaybeAutoPurgeForTenant`, TYLKO dla
`CONTACT_INTERACTIONS`. Dziedziczy identyczny prekontrakt co reszta tej metody (patrz
[[project_epic29_be113_retention_purge_service]]/[[feedback_dual_entry_point_tenantcontext_precontract]]).

**Odstępstwo świadome — `try/catch` wokół `countEligibleMessages`:** błąd liczenia wiadomości NIE
blokuje zapisu już policzonego `eligibleRowCount` kontakt+event (izolacja błędów, ten sam duch co
reszta klasy). Odkryte PODCZAS implementacji: bez tego `try/catch`, istniejący test
`errorForOneTenantInPartition_doesNotStopOtherTenantsInSamePartition` (stub
`retentionPolicyService.getRetentionMonths(TENANT_A, CONTACT_INTERACTIONS)` rzucający wyjątek,
współdzielony przez SKAN partycji i przez NOWY cutoff wiadomości) zaczął failować, bo wyjątek z
`countEligibleMessages` (który też woła `getRetentionMonths`) propagował PRZED `summaryRepository.upsert`.

**Auto-purge — efekt boczny (korzystny, nietestowany explicite w AC, ale pokryty testem
`messagesAloneCanTriggerAutoPurge`):** tenant z ZEREM eligible `contact`/`contact_event`, ale
niezerowymi sierotami e-mail, teraz PRAWIDŁOWO wyzwala `retentionPurgeService.purge(...)` dla
`CONTACT_INTERACTIONS` (przed BE-128 auto-purge nigdy by się nie odpalił tylko z powodu sierot, bo
`eligibleRowCount` zostawało 0) — sweep sierot z BE-127 faktycznie zaczyna działać automatycznie.

**Testy:** mock `RetentionEvaluationServiceImplTest$MessagesInEligibleRowCount` (7 testów) + real-DB
regresja BE-112 na granulacji repozytorium (nie całego serwisu — patrz uzasadnienie w notatce BE-128
w `TASKS-BACKEND.md`): `EmailMessageOrphanPurgeIntegrationTest`/`SocialMessageOrphanPurgeIntegrationTest`,
nowy nested `CountLinkedToContacts` + rozszerzony `TenantContextHandling`.

**How to apply:** BE-130 (D1=C) — `countEligibleMessages` i dwie nowe metody repozytoriów są
naturalnym punktem przeniesienia do kategorii `MESSAGE_CONTENT`. BE-133/BE-135 (DB-065/DB-067,
partycjonowanie `email_message`/`social_message`) — zastąp JOIN/IN-subquery przejściem na
`PartitionScanner` (komentarz Javadoc już to zaznacza). Powiązane:
[[project_epic30_be127_orphan_message_purge]], [[project-epic30-be124-message-retention-adr]].
