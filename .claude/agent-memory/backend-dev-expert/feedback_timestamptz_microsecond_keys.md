---
name: Klucz czasowy w PK/partycji: precyzja mikrosekund (Java Instant vs PostgreSQL)
description: timestamptz zapisuje µs — Instant z nanosekundami wraca zaokrąglony i nie trafia w wiersz po kluczu; obcinaj u źródła (truncatedTo(MICROS)) i waliduj w repozytorium
metadata:
  type: feedback
---

PostgreSQL przechowuje `timestamptz` z dokładnością do mikrosekund. Java `Instant.now()` na Linux/JDK 21 bywa z większą precyzją. Wartość użyta jako część klucza (PK/partycja, np. `message_at`) musi być równa temu, co wraca z bazy — inaczej `findById(id, at)`, `UPDATE … WHERE message_at = ?` i porównania w `EmailEvent` się nie zgadzają.

**Why:** BE-134 (`email_message`, klucz `(message_id, message_at)`). Decyzja: źródła obcinają do µs (`parseMessage` z INTERNALDATE, `sendNew`/`sendReply` z `sentAt`); repozytorium `save` rzuca `IllegalArgumentException` przy `null` albo nanosekundach. Test `saveRejectsMissingOrNonMicrosecondMessageAt` tego pilnuje.

**How to apply:** każdy `Instant` używany jako składnik klucza partycjonującego — `truncatedTo(ChronoUnit.MICROS)` w miejscu tworzenia i kontrakt w repozytorium. `java.util.Date` (INTERNALDATE) ma ms — już zgodne. Przy odczycie natywnym `timestamptz` może przyjść `java.sql.Timestamp`, `OffsetDateTime` albo `Instant` — obsłuż wszystkie trzy (`toInstant` w `EmailMessageRepository`).

Powiązane: [[project_be134_email_message_composite_key]].
