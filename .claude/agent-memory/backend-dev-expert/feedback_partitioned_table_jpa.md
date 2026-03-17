---
name: JPA na tabelach partycjonowanych PostgreSQL
description: JPA/Hibernate nie obsługuje poprawnie INSERT na tabelach partycjonowanych PostgreSQL z kluczem głównym zawierającym kolumnę partycjonowania – wymagany natywny SQL
type: feedback
---

Tabelę `audit_log` partycjonowaną po `created_at` (RANGE, miesięcznie) z PK `(log_id, created_at)` należy obsługiwać przez:

1. **Encja z `@IdClass`** – klucz złożony `(log_id, created_at)` wymagany przez PostgreSQL dla tabel partycjonowanych.
2. **Native INSERT** – `@Modifying @Query(nativeQuery=true)` z `CAST(:param AS uuid/jsonb/inet)` dla typów PostgreSQL-specific.
3. **Odczyt przez JPQL** – standard Spring Data działa (Hibernate odpytuje tabelę nadrzędną, która deleguje do partycji).

**Why:** PostgreSQL nie pozwala na standardowy JPA INSERT gdy PK zawiera kolumnę partycjonowania; Hibernate próbuje zrobić UPSERT który failuje.

**How to apply:** Każda nowa encja mapująca tabelę partycjonowaną → `@IdClass`, natywny INSERT w repozytorium, odczyt przez JPQL.
