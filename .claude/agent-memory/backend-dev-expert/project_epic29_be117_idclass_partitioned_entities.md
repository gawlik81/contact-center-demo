---
name: project_epic29_be117_idclass_partitioned_entities
description: EPIC-29 BE-117 – migracja ContactEvent/ContactAiSummary na @IdClass po partycjonowaniu DB-049/050/051; wzorzec do naśladowania dla przyszłych partycjonowanych encji JPA
metadata:
  type: project
---

BE-117 (EPIC-29 „Partycjonowanie i retencja danych z obsługi kontaktów") ukończone
2026-08-10. Migracje DB-049/050/051 (Flyway V085/V086/V087) przekształciły `contact_event`,
`contact_transcription`, `contact_ai_summary` w tabele RANGE-partycjonowane, co wymusiło
złożony PK (PostgreSQL wymaga kolumny partycjonowania w PRIMARY KEY).

**Wzorzec zastosowany 1:1 z istniejącego `Contact`/[[ContactId]]:**
- Nowa klasa `XxxId implements Serializable` z polami odpowiadającymi kolumnom PK,
  `@NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode` (Lombok wystarcza, bez ręcznego
  equals/hashCode).
- Encja: `@IdClass(XxxId.class)`, oba pola oznaczone `@Id` (nie tylko techniczne id, także
  kolumna partycjonowania).

**Wynik per tabela:**
- `ContactEvent`: PK `(event_id, started_at)` → `ContactEventId`.
- `ContactAiSummary`: PK `(ai_summary_id, generated_at)` → `ContactAiSummaryId`. UWAGA: kolumna
  partycjonowania to `generated_at` (moment wygenerowania treści przez model AI), **NIE**
  `created_at` (techniczny znacznik zapisu) — świadoma decyzja z DB-051/migracja V087.
- `contact_transcription`: PK `(transcription_id, created_at)`, ale **brak encji JPA**
  (`ContactTranscriptionRepository` to czysty `JdbcTemplate`) — więc `@IdClass` nie dotyczy tej
  tabeli w ogóle, tylko upewniono się, że `created_at` jest zawsze jawnie ustawiane z Javy
  (`Instant.now()`) w INSERT, zamiast polegać na `DEFAULT NOW()` w DB.

**Analiza UPDATE/DELETE adresujących wiersz po PK (kluczowa część kryteriów akceptacji):**
Nie każde UPDATE/DELETE po `id` wymaga dodania kolumny partycjonowania do WHERE — tylko te,
które identyfikują wiersz WYŁĄCZNIE przez tę jedną kolumnę. `ContactEventRepository.closeLastOpen`
(UPDATE) filtruje po `contact_id`/`tenant_id`/`stage`/`ended_at IS NULL` (nie tylko `event_id`) —
to wystarcza do semantycznej poprawności bez `started_at` w WHERE; jedyny koszt to brak partition
pruning (akceptowalne przy dzisiejszej garstce partycji). Nie zmieniono tego zapytania — decyzja
udokumentowana w javadoc metody. Zasada na przyszłość: zawsze sprawdzić krytycznie, czy WHERE
faktycznie adresuje wiersz "tylko po id", zanim doda się kolumnę partycjonowania automatycznie.

**Korekta łańcucha zależności ticketów (ważne dla kolejności prac):** mimo że `BE-117.Blokuje`
historycznie wymieniało też BE-112, `BE-112.Zależy od` nigdy nie deklarował BE-117 — BE-112
(agregacje/liczenie) operuje na natywnych zapytaniach COUNT per partycja, nie na encjach JPA, więc
nie jest wrażliwy na `@IdClass`. Tylko **BE-113** (silnik usuwania przez encje JPA) faktycznie
zależy od tej migracji. Poprawiono w TASKS-BACKEND.md 2026-08-09.

Zobacz też [[project_partitioned_table_jpa]] (jeśli istnieje — ogólny wzorzec Contact/ContactId)
oraz `feedback_partitioned_table_jpa.md` w tym katalogu.
