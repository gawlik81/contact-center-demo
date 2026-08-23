---
name: feedback_partitioned_table_ctid_delete_pitfall
description: DELETE ... WHERE ctid IN (subquery LIMIT N) jest NIEBEZPIECZNE na tabelach partycjonowanych PostgreSQL - ctid koliduje między partycjami
type: feedback
---

**Nigdy nie używaj `ctid` do identyfikacji wierszy w batchowanym DELETE/UPDATE na tabeli
partycjonowanej PostgreSQL** — nawet jeśli subquery filtruje poprawnie po `tenant_id`/innych
kolumnach z `LIMIT N`.

**Why:** `ctid` (fizyczny adres blok+offset) NIE jest unikalny globalnie na tabeli partycjonowanej
— każda partycja ma własną, niezależną numerację `ctid` zaczynającą się od `(0,0)`. Ta sama para
współrzędnych może więc wystąpić jednocześnie w wielu różnych partycjach. Wzorzec
`DELETE FROM parent WHERE ctid IN (SELECT ctid FROM parent WHERE tenant_id=? AND ... LIMIT 100)`
wygląda niewinnie i kompiluje się bez błędu, ale **dopasowuje wiersze z INNYCH partycji o
przypadkowo identycznym `ctid`**, niezwiązane z oryginalnym filtrem.

Zweryfikowano empirycznie przy BE-113 (EPIC-29, silnik retencji) na żywej instancji PostgreSQL z
tabelą `contact` (partycjonowana RANGE po `started_at`, 2 tenanci we wspólnej partycji miesięcznej):
subquery ograniczone do jednego tenanta w partycji `contact_2026_05` z `LIMIT 100` usunęło **168
wierszy w całej tabeli** (wszystkie miesiące, nie tylko maj) zamiast zamierzonych 100 — nadmiarowe
usunięcia trafiły w wiersze innych partycji przez przypadkową kolizję `ctid`. Drugi tenant we
wspólnej partycji akurat NIE ucierpiał w tym konkretnym przebiegu, ale to kwestia przypadku (który
`ctid` akurat kolidował), nie gwarancji — przy innych danych mogłoby dojść do **usunięcia cudzych
danych innego tenanta (cross-tenant data loss)**.

**How to apply:** Przy każdym batchowanym DELETE/UPDATE na tabeli partycjonowanej identyfikuj
wiersze przez PEŁNY klucz główny (kolumna techniczna ID + kolumna partycjonowania), NIGDY przez
`ctid`. Bezpieczny, zweryfikowany wzorzec:
```sql
WITH batch AS (
    SELECT <pk_id>, <partition_column> FROM <table>
    WHERE tenant_id = :tenantId AND <partition_column> < :cutoff
    ORDER BY <partition_column>
    LIMIT :batchSize
)
DELETE FROM <table> t
USING batch b
WHERE t.<pk_id> = b.<pk_id> AND t.<partition_column> = b.<partition_column>
RETURNING t.<pk_id>
```
Ten wzorzec zweryfikowano jako poprawny — w tym samym eksperymencie usunął dokładnie zamierzoną
liczbę wierszy, wyłącznie dla właściwego tenanta.

Zastosowane w `ContactRepository.deleteBatchOlderThan`, `ContactEventRepository.deleteBatchOlderThan`,
`ContactAiSummaryRepository.deleteBatchOlderThan`, `ContactTranscriptionRepository.deleteBatchOlderThan`
(BE-113). Sprawdź te metody jako referencyjny wzorzec przy każdej przyszłej pracy z batchowanym
usuwaniem na tabelach partycjonowanych (`contact`, `contact_event`, `contact_transcription`,
`contact_ai_summary`, przyszłe partycjonowane tabele).

**Uwaga metodologiczna:** projekt nie ma Testcontainers/H2 skonfigurowanych dla testów repozytoriów
(patrz [[feedback_repository_tests]]) — ta klasa błędów jest NIEWYKRYWALNA przez mockowany
`EntityManager` (mock nie sprawdza semantyki SQL względem realnych partycji). Jedyny sposób
weryfikacji to manualny test na żywej instancji Postgres z rzeczywistym partycjonowaniem — wykonano
to poza automatycznym zestawem testów przy BE-113. Rozważ to przy każdej przyszłej zmianie zapytań
DELETE/UPDATE na tabelach partycjonowanych, szczególnie jeśli ktoś zaproponuje "uproszczenie" przez
`ctid`.
