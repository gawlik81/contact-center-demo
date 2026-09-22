---
name: feedback_uuid_compareto_vs_postgres_order
description: java.util.UUID#compareTo() NIE jest spójne z porządkiem ORDER BY na kolumnie uuid w PostgreSQL — testy porządku sortowania muszą porównywać po UUID::toString, nie przez sorted() na UUID
type: feedback
---

**`UUID.compareTo()` (Java) porównuje `mostSigBits`/`leastSigBits` jako liczby ZE ZNAKIEM (long),
podczas gdy PostgreSQL porównuje kolumnę `uuid` bajt po bajcie (bez znaku).** Dla losowych UUID
(`UUID.randomUUID()`) te dwa porządki się rozjeżdżają, gdy najstarszy bit odpowiedniego `long`
różni się między UUID-ami — czyli praktycznie zawsze przy wystarczającej liczbie próbek.

**Why:** odkryte przy pisaniu testu `ContactRepositoryPurgeCandidatesIntegrationTest`
(BE-126, EPIC-30) dla `ContactRepository#findContactIdsOlderThan` — test asertował porządek
zwrócony przez `ORDER BY started_at, contact_id` (Postgres) względem `ids.stream().sorted().toList()`
(Java, naturalny `Comparable<UUID>`). Test failował deterministycznie mimo poprawnej implementacji
SQL — to była pułapka w TEŚCIE, nie bug produkcyjny.

**How to apply:** przy asercjach porządku sortowania po kolumnie `uuid` zwróconego z Postgresa,
buduj oczekiwaną kolejność przez `Comparator.comparing(UUID::toString)` (reprezentacja kanoniczna
tekstowa odpowiada porządkowi bajtowemu bazy), NIE przez `Collections.sort`/`Stream.sorted()` bez
komparatora (który używa `UUID.compareTo()`). Dotyczy każdego przyszłego testu z tie-breakiem po
UUID (np. kursory keyset, `ORDER BY ..., id`).
