---
name: project_be133_social_message_partition_retention
description: BE-133 (EPIC-30) — podpięcie social_message do PartitionReclaimJob/PartitionMaintenanceJob/RetentionEvaluationServiceImpl; product owner świadomie NIE czekał na BE-123 (ReclaimTarget/DropMode)
metadata:
  type: project
---

BE-133 (2026-10-01, czwarty ticket łańcucha DB-064 → DB-065 → BE-132 → BE-133) podpiął
`social_message` (partycjonowana od DB-065/V100) pod istniejącą maszynerię partycji/retencji —
**bez** czekania na formalnie zadeklarowaną zależność BE-123 (`ReclaimTarget`/`ThresholdSource`/
`DropMode.ONLY_IF_EMPTY`), bo BE-123 nie jest zaimplementowane i product owner zdecydował się nie
czekać. `PartitionReclaimJob.warnIfStillHasRows` (BE-145, już istniejące) blokuje bezwarunkowo DROP
NIEPUSTEJ partycji dla KAŻDEGO wpisu `TABLE_CATEGORIES` — to jest dokładnie semantyka
`DropMode.ONLY_IF_EMPTY` z przyszłego BE-123, tylko bez nazwy/konfigurowalności. `PartitionScanner`/
`PartitionScannerImpl` były już w 100% generyczne po nazwie tabeli — zero zmian w tych klasach.

**Trzy punktowe zmiany (main):**
1. `PartitionReclaimJob.TABLE_CATEGORIES` += `social_message` → `CONTACT_INTERACTIONS`.
2. `PartitionMaintenanceJob.PARTITIONED_TABLES` += `"social_message"` (6→7 tabel).
3. `RetentionEvaluationServiceImpl.PARTITION_AWARE_TABLES[CONTACT_INTERACTIONS]` += `"social_message"`.

**Czwarta zmiana, NIE w oryginalnej liście 3 punktów, ale wymuszona przez AC #4 i javadoc
BE-128/`RetentionSummaryDto` napisany WCZEŚNIEJ specjalnie pod tę migrację** ("Po skonwertowaniu
email_message/social_message na tabele partycjonowane (DB-065/DB-067) ten składnik przejdzie na
PartitionScanner (BE-133/BE-135)"): `RetentionEvaluationServiceImpl#countEligibleMessages` PRZESTAŁ
doliczać `socialMessageService.countOrphansOlderThan`/`countLinkedToContactsOlderThan` — bez tego
social_message byłaby liczona DWA RAZY (raz przez `scanPartitionAwareCategory` z punktu 3, raz przez
starą ścieżkę JOIN/IN-subquery). `SocialMessageService` jako zależność usunięta z klasy. `email_message`
ZOSTAJE na starej (dokładnej, wiersz-po-wierszu) ścieżce do czasu BE-135 — `SocialMessageService
#countOrphansOlderThan`/`#countLinkedToContactsOlderThan` same NIE zostały usunięte (brak innych
callerów w main, ale usunięcie ich też to osobny refaktor poza zakresem, zostawione jako martwy kod
do ewentualnego sprzątnięcia później).

**Konsekwencja semantyczna:** udział `social_message` w `eligibleRowCount` CONTACT_INTERACTIONS
przeszedł z "DOKŁADNY" (wiersz-po-wierszu, orphan+linked) na "KONSERWATYWNE PRZYBLIŻENIE granicą
partycji miesięcznej" — identyczny trade-off jak dla `contact`/`contact_event` od BE-112/EPIC-29
(opisane w `RetentionSummaryDto` BE128-02). Nie wpływa na bezpieczeństwo faktycznego purge.

**Testy dodane:** `PartitionReclaimJobTest$SocialMessagePartitionReclaim` (mock, 3 scenariusze),
`PartitionReclaimJobIntegrationTest` (4 nowe testy real-DB: nonEmpty/empty social_message + WP-5
"Poziom 1 przed Poziomem 2"), nowy plik `PartitionMaintenanceJobIntegrationTest` (real-DB, AC
#1/WP-1/WP-6 — nie istniał wcześniej żaden integration test dla tego joba, tylko mockowany unit).
Zobacz [[feedback_shared_db_integration_test_partition_leak]] po pułapkę odkrytą przy pisaniu testu
"nonEmpty blocks DROP" dla social_message (leak psujący `SocialMessageOrphanPurgeIntegrationTest`).

**WP-4 (local-demo) jawnie NIEUKOŃCZONE** — wymaga przebudowy obrazów Docker, odłożone zgodnie z
briefem zadania (nie wymagane w tej sesji).

Powiązane: [[project_be145_partition_reclaim_drop_guard]], [[project_be132_social_message_idclass]],
[[project_epic30_be128_message_count_dashboard]].
