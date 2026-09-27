---
name: feedback_tenant_prefixed_unique_index_reuse
description: Przed projektowaniem nowej migracji/indeksu dla per-tenant COUNT/JOIN na współdzielonej multi-tenant tabeli — sprawdź EXPLAIN pod realistyczną wielotenantową selektywnością; istniejący indeks z tenant_id jako pierwszą kolumną (nawet zbudowany z innego powodu, np. UNIQUE dedup) może być wystarczający
type: feedback
---

Gdy nowe zapytanie musi policzyć/przejrzeć wiersze JEDNEGO tenanta w tabeli współdzielonej przez
WSZYSTKICH tenantów (np. `email_message`, `social_message` — bez partycjonowania po tenant_id), nie
zakładaj od razu, że potrzebna jest nowa dedykowana migracja/indeks, ani że PostgreSQL zrobi `Seq
Scan` całej tabeli niezależnie od wielkości platformy.

**Why:** przy BE-128 (EPIC-30, 2026-09-26) sprawdzono EMPIRYCZNIE (nie zgadnięto) koszt zapytania
`COUNT(*) FROM email_message WHERE tenant_id=? AND contact_id IN (subquery)`. Na scratch DB z JEDNYM
tenantem (100% udziału w tabeli) planner wybierał `Parallel Seq Scan` — potwierdzało to naiwną obawę
"to zawsze skanuje całą tabelę". Po dodaniu 9 tenantów "szumu" (tenant docelowy spadł do ~10% udziału
— REALISTYCZNA wielotenantowa selektywność, nie sztuczny 1-tenantowy scratch) planner PRZESTAWIŁ SIĘ
na `Bitmap Index Scan` na `uq_email_message_id_header` — unikalny indeks `(tenant_id,
message_id_header)` z migracji V010, zbudowany z powodu deduplikacji IMAP, NIE dla tego zapytania —
mimo to `tenant_id` jako PIERWSZA kolumna wystarczyło, żeby ograniczyć koszt do wierszy TEGO tenanta.
Analogicznie `idx_social_message_sender (tenant_id, platform, sender_external_id)`. Efekt: ŻADNA
nowa migracja SQL nie była potrzebna, a AC ticketu ("brak migracji") zostało spełnione bez
kompromisu na dokładności liczenia (nie trzeba było iść w oszacowanie).

**How to apply:**
1. Przy nowym zapytaniu per-tenant na współdzielonej tabeli: `\d <tabela>` i sprawdź, czy JAKIKOLWIK
   istniejący indeks/UNIQUE constraint ma `tenant_id` jako PIERWSZĄ kolumnę — nawet zbudowany z
   innego powodu (dedup, FK lookup) kwalifikuje się jako kandydat.
2. Test na scratch DB z JEDNYM tenantem jest MISLEADING (100% selektywność zawsze faworyzuje Seq
   Scan) — zawsze symuluj wielotenantową selektywność (kilka-kilkanaście tenantów "szumu" o
   podobnym wolumenie, tenant docelowy = niewielki % całości) PRZED wnioskiem "potrzebna nowa
   migracja" albo "trzeba oszacowanie".
3. Metodologia „EXPLAIN na scratch" (dodatkowa baza WEWNĄTRZ działającego kontenera, `pg_dump -s` +
   ręczne zastosowanie migracji, USUNIĘTA po pracy) — patrz [[feedback-jpa-real-db-integration-test-harness]]
   i notatka BE-127/BE-128 w `TASKS-BACKEND.md`.
4. Automatyczny test CI asertujący "plan MUSI użyć indeksu X" jest UZASADNIONY tylko dla indeksu
   ZBUDOWANEGO CELOWO pod to zapytanie (np. `idx_email_message_tenant_orphan_age` z DB-059/BE-127) —
   dla indeksu ogólnego przeznaczenia reużytego "przy okazji" (jak tutaj) taka asercja jest zależna
   od heurystyk plannera (statystyki, `work_mem`, liczba workerów) i ryzykuje flaky test; udowodnij
   koszt RAZ, manualnie, i zapisz dowód w notatce ticketu — nie zapisuj w automatycznym teście.
